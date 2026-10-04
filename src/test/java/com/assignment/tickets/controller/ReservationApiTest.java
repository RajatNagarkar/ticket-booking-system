package com.assignment.tickets.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.tickets.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class ReservationApiTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reservesSeatsAndConfirmsThem() {
        String showId = createShow(List.of("A1", "A2", "A3"), 25000);

        ResponseEntity<JsonNode> response = reserve(showId, "alice", List.of("A2", "A1"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.get("reservation_id").asText()).isNotBlank();
        assertThat(body.get("show_id").asText()).isEqualTo(showId);
        assertThat(body.get("user_id").asText()).isEqualTo("alice");
        assertThat(body.get("seats")).extracting(JsonNode::asText).containsExactly("A1", "A2");
        assertThat(body.get("amount_paise").asLong()).isEqualTo(50000);
        assertThat(body.get("status").asText()).isEqualTo("confirmed");

        JsonNode show = getShow(showId);
        assertThat(show.at("/counts/confirmed").asInt()).isEqualTo(2);
        assertThat(show.at("/counts/available").asInt()).isEqualTo(1);
    }

    @Test
    void takenSeatIs409() {
        String showId = createShow(List.of("A1"), 100);
        reserve(showId, "alice", List.of("A1"), key());

        ResponseEntity<JsonNode> response = reserve(showId, "bob", List.of("A1"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error").asText()).isEqualTo("seat_taken");
    }

    @Test
    void partialAvailabilityReservesNothing() {
        String showId = createShow(List.of("A1", "A2"), 100);
        reserve(showId, "alice", List.of("A2"), key());

        ResponseEntity<JsonNode> response = reserve(showId, "bob", List.of("A1", "A2"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seatStatus(getShow(showId), "A1")).isEqualTo("available");
        assertThat(reservationCount(showId)).isEqualTo(1);
    }

    @Test
    void unknownSeatIs400() {
        String showId = createShow(List.of("A1"), 100);

        ResponseEntity<JsonNode> response = reserve(showId, "alice", List.of("A1", "Z9"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).isEqualTo("unknown_seat");
        assertThat(seatStatus(getShow(showId), "A1")).isEqualTo("available");
    }

    @Test
    void unknownShowIs404() {
        ResponseEntity<JsonNode> response = reserve(UUID.randomUUID().toString(), "alice", List.of("A1"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingUserIs401() {
        String showId = createShow(List.of("A1"), 100);

        ResponseEntity<JsonNode> response = reserve(showId, null, List.of("A1"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void duplicateSeatsInRequestAre400() {
        String showId = createShow(List.of("A1"), 100);

        ResponseEntity<JsonNode> response = reserve(showId, "alice", List.of("A1", "A1"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).isEqualTo("duplicate_seats");
    }

    @Test
    void hotSeatRaceHasExactlyOneWinner() throws Exception {
        String showId = createShow(List.of("A12"), 25000);
        int racers = 500;

        List<ResponseEntity<JsonNode>> responses = race(racers,
                i -> reserve(showId, "user-" + i, List.of("A12"), key()));

        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT).hasSize(racers - 1)
                .allSatisfy(r -> assertThat(r.getBody().get("error").asText()).isEqualTo("seat_taken"));
        assertThat(reservationCount(showId)).isEqualTo(1);
        assertInvariant(showId, 1);
    }

    @Test
    void overlappingMultiSeatRequestsNeverDeadlockOrSplit() throws Exception {
        List<String> seatNos = List.of("A1", "A2", "A3", "A4", "A5", "A6");
        String showId = createShow(seatNos, 100);

        List<ResponseEntity<JsonNode>> responses = race(300, i -> {
            List<String> wanted = new ArrayList<>(seatNos);
            Collections.shuffle(wanted);
            int size = ThreadLocalRandom.current().nextInt(2, 4);
            return reserve(showId, "user-" + i, wanted.subList(0, size), key());
        });

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().is5xxServerError()).isFalse());
        long winners = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        assertThat(reservationCount(showId)).isEqualTo(winners);
        // All-or-nothing: every surviving reservation owns exactly the seats it asked for.
        Integer split = jdbc.queryForObject("""
                SELECT count(*) FROM reservations r
                WHERE r.show_id = ?::uuid
                  AND cardinality(r.seats) <> (SELECT count(*) FROM seats s WHERE s.reservation_id = r.id)
                """, Integer.class, showId);
        assertThat(split).isZero();
        assertInvariant(showId, seatNos.size());
    }

    @Test
    void retryWithSameKeyReturnsOriginalReservation() {
        String showId = createShow(List.of("A1", "A2"), 100);
        String key = key();

        ResponseEntity<JsonNode> first = reserve(showId, "alice", List.of("A1", "A2"), key);
        ResponseEntity<JsonNode> retry = reserve(showId, "alice", List.of("A2", "A1"), key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(reservationCount(showId)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentSeatsIs409() {
        String showId = createShow(List.of("A1", "A2"), 100);
        String key = key();
        reserve(showId, "alice", List.of("A1"), key);

        ResponseEntity<JsonNode> response = reserve(showId, "alice", List.of("A2"), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error").asText()).isEqualTo("idempotency_key_reused");
        assertThat(seatStatus(getShow(showId), "A2")).isEqualTo("available");
        assertThat(reservationCount(showId)).isEqualTo(1);
    }

    @Test
    void sameKeyOnDifferentShowIs409() {
        String showA = createShow(List.of("A1"), 100);
        String showB = createShow(List.of("A1"), 100);
        String key = key();
        reserve(showA, "alice", List.of("A1"), key);

        ResponseEntity<JsonNode> response = reserve(showB, "alice", List.of("A1"), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error").asText()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void keyIsScopedPerUser() {
        String showId = createShow(List.of("A1", "A2"), 100);
        String key = key();

        ResponseEntity<JsonNode> alice = reserve(showId, "alice", List.of("A1"), key);
        ResponseEntity<JsonNode> bob = reserve(showId, "bob", List.of("A2"), key);

        assertThat(alice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bob.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bob.getBody().get("reservation_id")).isNotEqualTo(alice.getBody().get("reservation_id"));
    }

    @Test
    void declinedAttemptDoesNotBurnTheKey() {
        String showId = createShow(List.of("A1", "A2"), 100);
        reserve(showId, "bob", List.of("A1"), key());
        String key = key();

        ResponseEntity<JsonNode> declined = reserve(showId, "alice", List.of("A1"), key);
        ResponseEntity<JsonNode> retry = reserve(showId, "alice", List.of("A2"), key);

        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(declined.getBody().get("error").asText()).isEqualTo("seat_taken");
        // Nothing was stored for the declined attempt, so the key is still free for a new body.
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(reservationCount(showId)).isEqualTo(2);
    }

    @Test
    void concurrentRetriesWithSameKeyReserveExactlyOnce() throws Exception {
        String showId = createShow(List.of("A1", "A2"), 100);
        String key = key();

        List<ResponseEntity<JsonNode>> responses = race(50,
                i -> reserve(showId, "alice", List.of("A1", "A2"), key));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses).extracting(r -> r.getBody().get("reservation_id").asText()).containsOnly(
                responses.get(0).getBody().get("reservation_id").asText());
        assertThat(responses).filteredOn(r -> "false".equals(r.getHeaders().getFirst("Idempotent-Replayed")))
                .hasSize(1);
        assertThat(reservationCount(showId)).isEqualTo(1);
        assertInvariant(showId, 2);
    }

    @Test
    void concurrentSameKeyWithDifferentSeatsHasOneWinner() throws Exception {
        String showId = createShow(List.of("A1", "A2", "A3", "A4"), 100);
        List<String> seatNos = List.of("A1", "A2", "A3", "A4");
        String key = key();

        List<ResponseEntity<JsonNode>> responses = race(40,
                i -> reserve(showId, "alice", List.of(seatNos.get(i % seatNos.size())), key));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().is5xxServerError()).isFalse());
        assertThat(reservationCount(showId)).isEqualTo(1);
        assertThat(getShow(showId).at("/counts/confirmed").asInt()).isEqualTo(1);
        // Winner plus its identical retries get 201; every other body on that key gets 409.
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(10);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT).hasSize(30)
                .allSatisfy(r -> assertThat(r.getBody().get("error").asText()).isEqualTo("idempotency_key_reused"));
    }

    @Test
    void requestLargerThanLimitIs409() {
        String showId = createShow(List.of("A1", "A2", "A3"), 100, 2);

        ResponseEntity<JsonNode> response = reserve(showId, "alice", List.of("A1", "A2", "A3"), key());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error").asText()).isEqualTo("per_user_limit");
        assertInvariant(showId, 3);
        assertThat(getShow(showId).at("/counts/available").asInt()).isEqualTo(3);
    }

    @Test
    void limitIsCumulativeAcrossReservations() {
        String showId = createShow(List.of("A1", "A2", "A3"), 100, 2);
        reserve(showId, "alice", List.of("A1"), key());

        ResponseEntity<JsonNode> overLimit = reserve(showId, "alice", List.of("A2", "A3"), key());
        ResponseEntity<JsonNode> withinLimit = reserve(showId, "alice", List.of("A2"), key());

        assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(overLimit.getBody().get("error").asText()).isEqualTo("per_user_limit");
        assertThat(withinLimit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(heldBy(showId, "alice")).isEqualTo(2);
    }

    @Test
    void limitIsPerUser() {
        String showId = createShow(List.of("A1", "A2"), 100, 1);

        assertThat(reserve(showId, "alice", List.of("A1"), key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(showId, "bob", List.of("A2"), key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void declinedSeatDoesNotConsumeQuota() {
        String showId = createShow(List.of("A1", "A2"), 100, 1);
        reserve(showId, "bob", List.of("A1"), key());

        ResponseEntity<JsonNode> declined = reserve(showId, "alice", List.of("A1"), key());
        ResponseEntity<JsonNode> next = reserve(showId, "alice", List.of("A2"), key());

        assertThat(declined.getBody().get("error").asText()).isEqualTo("seat_taken");
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(heldBy(showId, "alice")).isEqualTo(1);
    }

    @Test
    void replayDoesNotConsumeQuota() {
        String showId = createShow(List.of("A1"), 100, 1);
        String key = key();
        reserve(showId, "alice", List.of("A1"), key);

        ResponseEntity<JsonNode> retry = reserve(showId, "alice", List.of("A1"), key);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(heldBy(showId, "alice")).isEqualTo(1);
    }

    @Test
    void parallelRequestsFromOneUserNeverExceedLimit() throws Exception {
        List<String> seatNos = IntStream.rangeClosed(1, 10).mapToObj(i -> "A" + i).toList();
        String showId = createShow(seatNos, 100, 4);

        List<ResponseEntity<JsonNode>> responses = race(10,
                i -> reserve(showId, "alice", List.of(seatNos.get(i)), key()));

        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(4);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT).hasSize(6)
                .allSatisfy(r -> assertThat(r.getBody().get("error").asText()).isEqualTo("per_user_limit"));
        assertThat(confirmedSeatsOf(showId, "alice")).isEqualTo(4);
        assertThat(heldBy(showId, "alice")).isEqualTo(4);
        assertInvariant(showId, 10);
    }

    @Test
    void parallelMultiSeatRequestsFromOneUserNeverExceedLimit() throws Exception {
        List<String> seatNos = IntStream.rangeClosed(1, 20).mapToObj(i -> "A" + i).toList();
        String showId = createShow(seatNos, 100, 4);

        List<ResponseEntity<JsonNode>> responses = race(10,
                i -> reserve(showId, "alice", seatNos.subList(2 * i, 2 * i + 2), key()));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().is5xxServerError()).isFalse());
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(2);
        assertThat(confirmedSeatsOf(showId, "alice")).isEqualTo(4);
        assertThat(heldBy(showId, "alice")).isEqualTo(4);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private <T> List<T> race(int racers, Function<Integer, T> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = IntStream.range(0, racers)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return request.apply(i);
                    }))
                    .toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private void assertInvariant(String showId, int totalSeats) {
        JsonNode counts = getShow(showId).get("counts");
        assertThat(counts.get("available").asInt() + counts.get("held").asInt() + counts.get("confirmed").asInt())
                .isEqualTo(totalSeats);
        assertThat(counts.get("total").asInt()).isEqualTo(totalSeats);
    }

    private String createShow(List<String> seats, long pricePaise) {
        return createShow(seats, pricePaise, 4);
    }

    private String createShow(List<String> seats, long pricePaise, int perUserLimit) {
        return http.postForObject("/tbs/shows", Map.of("name", "show", "seats", seats,
                        "price_paise", pricePaise, "per_user_limit", perUserLimit), JsonNode.class)
                .get("id").asText();
    }

    private ResponseEntity<JsonNode> reserve(String showId, String userId, List<String> seats, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-User-Id", userId);
        }
        return http.exchange("/tbs/shows/" + showId + "/reserve", HttpMethod.POST,
                new HttpEntity<>(Map.of("seats", seats, "idempotency_key", key), headers), JsonNode.class);
    }

    private JsonNode getShow(String showId) {
        return http.getForObject("/tbs/shows/" + showId, JsonNode.class);
    }

    private static String seatStatus(JsonNode show, String seatNo) {
        for (JsonNode seat : show.get("seats")) {
            if (seat.get("seat_no").asText().equals(seatNo)) {
                return seat.get("status").asText();
            }
        }
        throw new AssertionError("No seat " + seatNo);
    }

    private int heldBy(String showId, String userId) {
        return jdbc.queryForObject("SELECT held FROM user_show_quota WHERE show_id = ?::uuid AND user_id = ?",
                Integer.class, showId, userId);
    }

    private int confirmedSeatsOf(String showId, String userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM seats WHERE show_id = ?::uuid AND user_id = ? AND status = 'confirmed'
                """, Integer.class, showId, userId);
    }

    private long reservationCount(String showId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", Long.class, showId);
    }
}

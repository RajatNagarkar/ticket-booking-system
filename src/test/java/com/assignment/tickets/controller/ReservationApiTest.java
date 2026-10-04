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
        return http.postForObject("/tbs/shows",
                Map.of("name", "show", "seats", seats, "price_paise", pricePaise), JsonNode.class)
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

    private long reservationCount(String showId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id = ?::uuid", Long.class, showId);
    }
}

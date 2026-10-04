package com.assignment.tickets.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.assignment.tickets.IntegrationTest;
import com.assignment.tickets.observability.RequestLoggingFilter;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class ObservabilityApiTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private static final Pattern SAMPLE = Pattern.compile("^(\\w+)\\{([^}]*)}\\s+(\\S+)$");

    @Test
    void countersAndSeatGaugeReconcileWithApiState() {
        String showId = createShow(List.of("A1", "A2", "A3", "A4"), 1);
        double confirmedBefore = metric("reservations_confirmed_total", Map.of());
        double takenBefore = metric("reservations_declined_total", Map.of("reason", "seat_taken"));
        double limitBefore = metric("reservations_declined_total", Map.of("reason", "per_user_limit"));
        double replayBefore = metric("reservations_declined_total", Map.of("reason", "idempotent_replay"));
        double reusedBefore = metric("reservations_declined_total", Map.of("reason", "idempotency_key_reused"));
        double cancelledBefore = metric("reservations_cancelled_total", Map.of());

        String aliceKey = UUID.randomUUID().toString();
        String aliceReservation = reserve(showId, "alice", "A1", aliceKey).getBody().get("reservation_id").asText();
        reserve(showId, "bob", "A2", UUID.randomUUID().toString());
        reserve(showId, "carol", "A3", UUID.randomUUID().toString());
        reserve(showId, "dave", "A1", UUID.randomUUID().toString());      // seat_taken
        reserve(showId, "alice", "A4", UUID.randomUUID().toString());     // per_user_limit (limit 1)
        reserve(showId, "alice", "A1", aliceKey);                         // idempotent_replay
        reserve(showId, "alice", "A4", aliceKey);                         // idempotency_key_reused
        post("/tbs/reservations/" + aliceReservation + "/cancel", null, "alice");
        post("/tbs/reservations/" + aliceReservation + "/cancel", null, "alice"); // already cancelled: not counted

        assertThat(metric("reservations_confirmed_total", Map.of()) - confirmedBefore).isEqualTo(3);
        assertThat(metric("reservations_declined_total", Map.of("reason", "seat_taken")) - takenBefore).isEqualTo(1);
        assertThat(metric("reservations_declined_total", Map.of("reason", "per_user_limit")) - limitBefore).isEqualTo(1);
        assertThat(metric("reservations_declined_total", Map.of("reason", "idempotent_replay")) - replayBefore).isEqualTo(1);
        assertThat(metric("reservations_declined_total", Map.of("reason", "idempotency_key_reused")) - reusedBefore).isEqualTo(1);
        assertThat(metric("reservations_cancelled_total", Map.of()) - cancelledBefore).isEqualTo(1);

        JsonNode counts = http.getForObject("/tbs/shows/" + showId, JsonNode.class).get("counts");
        assertThat(metric("seats", Map.of("show", showId, "status", "available"))).isEqualTo(counts.get("available").asDouble());
        assertThat(metric("seats", Map.of("show", showId, "status", "held"))).isEqualTo(counts.get("held").asDouble());
        assertThat(metric("seats", Map.of("show", showId, "status", "confirmed"))).isEqualTo(counts.get("confirmed").asDouble());
        assertThat(counts.get("available").asInt()).isEqualTo(2);
        assertThat(counts.get("confirmed").asInt()).isEqualTo(2);
    }

    @Test
    void seatGaugeOnlyReportsRecentShows() {
        String recent = createShow(List.of("A1"), 4);
        String old = createShow(List.of("A1"), 4);
        jdbc.update("UPDATE shows SET created_at = now() - interval '2 days' WHERE id = ?::uuid", old);

        String scrape = scrape();

        assertThat(scrape).contains("show=\"" + recent + "\"").doesNotContain("show=\"" + old + "\"");
    }

    @Test
    void readinessStaysUpWhileTheApplicationPoolIsExhausted() throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            // Take every connection the request pool has; a probe sharing that pool would block.
            for (int i = 0; i < 20; i++) {
                held.add(dataSource.getConnection());
            }
            long start = System.nanoTime();
            ResponseEntity<JsonNode> readiness = http.getForEntity("/tbs/actuator/health/readiness", JsonNode.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(elapsedMs).isLessThan(2_000);
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    @Test
    void latencyHistogramIsExposed() {
        http.getForEntity("/tbs/shows/" + UUID.randomUUID(), JsonNode.class);

        assertThat(scrape()).contains("http_server_requests_seconds_bucket");
    }

    @Test
    void probesReportUpAndReadinessChecksTheDatabase() {
        ResponseEntity<JsonNode> liveness = http.getForEntity("/tbs/actuator/health/liveness", JsonNode.class);
        ResponseEntity<JsonNode> readiness = http.getForEntity("/tbs/actuator/health/readiness", JsonNode.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody().at("/components/database/status").asText()).isEqualTo("UP");
        assertThat(liveness.getBody().has("components") && liveness.getBody().get("components").has("database")).isFalse();
    }

    @Test
    void requestIdIsGeneratedEchoedOrReplaced() {
        String generated = http.getForEntity("/tbs/shows/" + UUID.randomUUID(), JsonNode.class)
                .getHeaders().getFirst("X-Request-Id");
        assertThat(generated).isNotBlank();

        assertThat(getWithRequestId("trace-123").getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-123");
        assertThat(getWithRequestId("unsafe id; with spaces").getHeaders().getFirst("X-Request-Id"))
                .matches("[0-9a-f-]{36}");
        assertThat(getWithRequestId("x".repeat(65)).getHeaders().getFirst("X-Request-Id"))
                .matches("[0-9a-f-]{36}");
    }

    @Test
    void eachRequestWritesOneStructuredAccessLogLine() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String showId = createShow(List.of("A1"), 4);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokens.issue("alice", false).accessToken());
            headers.set("X-Request-Id", "log-test-1");
            http.exchange("/tbs/shows/" + showId + "/reserve", HttpMethod.POST,
                    new HttpEntity<>(Map.of("seats", List.of("A1"), "idempotency_key", UUID.randomUUID().toString()),
                            headers), JsonNode.class);

            ILoggingEvent line = awaitLogLine(appender, "log-test-1");
            Map<String, String> mdc = line.getMDCPropertyMap();
            assertThat(mdc).containsEntry("user_id", "alice")
                    .containsEntry("show_id", showId)
                    .containsEntry("outcome", "confirmed")
                    .containsKey("reservation_id");
            assertThat(Arrays.stream(line.getArgumentArray()).map(String::valueOf))
                    .contains("method=POST", "status=201", "path=/tbs/shows/" + showId + "/reserve")
                    .anyMatch(a -> a.startsWith("duration_ms="));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private ILoggingEvent awaitLogLine(ListAppender<ILoggingEvent> appender, String requestId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Optional<ILoggingEvent> line = appender.list.stream()
                    .filter(e -> requestId.equals(e.getMDCPropertyMap().get("request_id")))
                    .findFirst();
            if (line.isPresent()) {
                return line.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No access-log line for request " + requestId);
    }

    private ResponseEntity<JsonNode> getWithRequestId(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", requestId);
        return http.exchange("/tbs/shows/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private String createShow(List<String> seats, int perUserLimit) {
        return post("/tbs/shows", Map.of("name", "s", "seats", seats, "price_paise", 100,
                "per_user_limit", perUserLimit), ADMIN).getBody().get("id").asText();
    }

    private ResponseEntity<JsonNode> reserve(String showId, String userId, String seat, String key) {
        return post("/tbs/shows/" + showId + "/reserve", Map.of("seats", List.of(seat), "idempotency_key", key), userId);
    }

    private String scrape() {
        ResponseEntity<String> response = http.getForEntity("/tbs/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** Sum of every sample of {@code name} whose labels include {@code labels} (0 if none). */
    private double metric(String name, Map<String, String> labels) {
        double sum = 0;
        for (String line : scrape().split("\n")) {
            Matcher m = SAMPLE.matcher(line);
            if (m.matches() && m.group(1).equals(name)
                    && labels.entrySet().stream().allMatch(l -> m.group(2).contains(l.getKey() + "=\"" + l.getValue() + "\""))) {
                sum += Double.parseDouble(m.group(3));
            }
        }
        return sum;
    }
}

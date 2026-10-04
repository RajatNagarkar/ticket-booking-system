package com.assignment.tickets.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.tickets.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class AuthApiTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void tokenEndpointIssuesAUsableToken() {
        ResponseEntity<JsonNode> issued = post("/tbs/auth/token", Map.of("user_id", "alice"), null);

        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody().get("token_type").asText()).isEqualTo("Bearer");
        assertThat(issued.getBody().get("expires_in").asLong()).isPositive();

        String showId = createShow("A1");
        ResponseEntity<JsonNode> reserved = postWithToken("/tbs/shows/" + showId + "/reserve",
                reserveBody("A1"), issued.getBody().get("access_token").asText());
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserved.getBody().get("user_id").asText()).isEqualTo("alice");
    }

    @Test
    void adminTokenFromEndpointCanCreateShows() {
        String adminToken = post("/tbs/auth/token", Map.of("user_id", "ops", "role", "admin"), null)
                .getBody().get("access_token").asText();

        ResponseEntity<JsonNode> created = postWithToken("/tbs/shows",
                Map.of("name", "s", "seats", List.of("A1"), "price_paise", 100), adminToken);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void invalidTokenRequestIs400() {
        assertThat(post("/tbs/auth/token", Map.of("user_id", ""), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/tbs/auth/token", Map.of("user_id", "alice", "role", "root"), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reserveWithoutTokenIs401() {
        String showId = createShow("A1");

        ResponseEntity<JsonNode> response = post("/tbs/shows/" + showId + "/reserve", reserveBody("A1"), null);

        assertUnauthenticated(response);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    void malformedTokenIs401() {
        String showId = createShow("A1");

        assertUnauthenticated(postWithToken("/tbs/shows/" + showId + "/reserve", reserveBody("A1"), "not-a-jwt"));
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() {
        String showId = createShow("A1");
        String forged = sign("another-secret-that-is-32-bytes-long!!", "alice", "ticket-booking-system",
                Instant.now().plusSeconds(600), List.of("ADMIN", "USER"));

        assertUnauthenticated(postWithToken("/tbs/shows/" + showId + "/reserve", reserveBody("A1"), forged));
        assertUnauthenticated(postWithToken("/tbs/shows",
                Map.of("name", "s", "seats", List.of("A1"), "price_paise", 100), forged));
    }

    @Test
    void expiredTokenIs401() {
        String showId = createShow("A1");
        String expired = sign(JWT_SECRET, "alice", "ticket-booking-system",
                Instant.now().minusSeconds(3600), List.of("USER"));

        assertUnauthenticated(postWithToken("/tbs/shows/" + showId + "/reserve", reserveBody("A1"), expired));
    }

    @Test
    void tokenFromAnotherIssuerIs401() {
        String showId = createShow("A1");
        String foreign = sign(JWT_SECRET, "alice", "someone-else", Instant.now().plusSeconds(600), List.of("USER"));

        assertUnauthenticated(postWithToken("/tbs/shows/" + showId + "/reserve", reserveBody("A1"), foreign));
    }

    @Test
    void userCannotCreateShows() {
        ResponseEntity<JsonNode> response = post("/tbs/shows",
                Map.of("name", "s", "seats", List.of("A1"), "price_paise", 100), "alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error").asText()).isEqualTo("forbidden");
    }

    @Test
    void showsAndHealthArePublic() {
        String showId = createShow("A1");

        assertThat(http.getForEntity("/tbs/shows/" + showId, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/tbs/actuator/health", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void spoofedUserIdInBodyIsIgnored() {
        String showId = createShow("A1");
        Map<String, Object> body = new HashMap<>(reserveBody("A1"));
        body.put("user_id", "bob");

        ResponseEntity<JsonNode> response = post("/tbs/shows/" + showId + "/reserve", body, "alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("user_id").asText()).isEqualTo("alice");
        assertThat(jdbc.queryForObject("SELECT user_id FROM seats WHERE show_id = ?::uuid AND seat_no = 'A1'",
                String.class, showId)).isEqualTo("alice");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_show_quota WHERE show_id = ?::uuid AND user_id = 'bob'",
                Integer.class, showId)).isZero();
    }

    @Test
    void userCannotCancelAnotherUsersReservationEvenBySpoofingTheBody() {
        String showId = createShow("A1");
        String reservationId = post("/tbs/shows/" + showId + "/reserve", reserveBody("A1"), "alice")
                .getBody().get("reservation_id").asText();

        ResponseEntity<JsonNode> response = post("/tbs/reservations/" + reservationId + "/cancel",
                Map.of("user_id", "alice"), "bob");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ?::uuid AND seat_no = 'A1'",
                String.class, showId)).isEqualTo("confirmed");
    }

    private String createShow(String... seats) {
        return post("/tbs/shows", Map.of("name", "s", "seats", List.of(seats), "price_paise", 100), ADMIN)
                .getBody().get("id").asText();
    }

    private static Map<String, Object> reserveBody(String... seats) {
        return Map.of("seats", List.of(seats), "idempotency_key", UUID.randomUUID().toString());
    }

    private static void assertUnauthenticated(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error").asText()).isEqualTo("unauthenticated");
    }

    private static String sign(String secret, String subject, String issuer, Instant expiresAt, List<String> roles) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer(issuer)
                .issuedAt(expiresAt.minusSeconds(3600))
                .expiresAt(expiresAt)
                .claim("roles", roles)
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}

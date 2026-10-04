package com.assignment.tickets.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.assignment.tickets.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ShowApiTest extends IntegrationTest {

    @Test
    void createsShowWithEverySeatAvailable() {
        ResponseEntity<JsonNode> created = post("/tbs/shows", Map.of(
                "name", "friday-night",
                "seats", List.of("A1", "A2", "A3"),
                "price_paise", 25000), ADMIN);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        assertThat(body.get("id").asText()).isNotBlank();
        assertThat(body.get("name").asText()).isEqualTo("friday-night");
        assertThat(body.get("price_paise").asLong()).isEqualTo(25000);
        assertThat(body.get("per_user_limit").asInt()).isEqualTo(4);
        assertThat(body.get("total_seats").asInt()).isEqualTo(3);
        assertThat(body.at("/counts/available").asInt()).isEqualTo(3);
        assertThat(body.at("/counts/held").asInt()).isZero();
        assertThat(body.at("/counts/confirmed").asInt()).isZero();
        assertThat(body.get("seats")).hasSize(3)
                .allSatisfy(seat -> assertThat(seat.get("status").asText()).isEqualTo("available"));
    }

    @Test
    void getReturnsSameStateAsCreate() {
        JsonNode created = post("/tbs/shows", Map.of(
                "name", "matinee",
                "seats", List.of("B1", "B2"),
                "price_paise", 15000,
                "per_user_limit", 2), ADMIN).getBody();

        ResponseEntity<JsonNode> fetched = http.getForEntity("/tbs/shows/" + created.get("id").asText(), JsonNode.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created);
        assertThat(fetched.getBody().get("per_user_limit").asInt()).isEqualTo(2);
    }

    @Test
    void unknownShowIs404() {
        ResponseEntity<JsonNode> response = http.getForEntity("/tbs/shows/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error").asText()).isEqualTo("show_not_found");
    }

    @Test
    void malformedIdIs400() {
        ResponseEntity<JsonNode> response = http.getForEntity("/tbs/shows/not-a-uuid", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).isEqualTo("invalid_parameter");
    }

    @Test
    void duplicateSeatsAre400() {
        ResponseEntity<JsonNode> response = post("/tbs/shows", Map.of(
                "name", "dup",
                "seats", List.of("A1", "A1"),
                "price_paise", 100), ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).isEqualTo("duplicate_seats");
    }

    @Test
    void invalidBodyIs400() {
        ResponseEntity<JsonNode> response = post("/tbs/shows", Map.of(
                "name", "",
                "seats", List.of(),
                "price_paise", -1), ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").asText()).isEqualTo("validation_failed");
    }
}

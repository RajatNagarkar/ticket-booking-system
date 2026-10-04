package com.assignment.tickets.controller;

import com.assignment.tickets.dto.request.ReserveRequest;
import com.assignment.tickets.dto.response.ReservationOutcome;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.exception.MissingUserException;
import com.assignment.tickets.service.ReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReservationService service;

    /** A replayed retry returns the original reservation with 201, flagged by a response header. */
    // TODO(auth): user id comes from the JWT subject, not a header.
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID showId,
                                                       @RequestHeader(name = "X-User-Id", required = false) String userId,
                                                       @Valid @RequestBody ReserveRequest request) {
        ReservationOutcome outcome = service.reserve(showId, requireUser(userId), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.reservation());
    }

    /** Owner only. Cancelling twice is safe and returns the cancelled reservation again. */
    // TODO(auth): user id comes from the JWT subject, not a header.
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID reservationId,
                                      @RequestHeader(name = "X-User-Id", required = false) String userId) {
        return service.cancel(reservationId, requireUser(userId));
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new MissingUserException();
        }
        return userId;
    }
}

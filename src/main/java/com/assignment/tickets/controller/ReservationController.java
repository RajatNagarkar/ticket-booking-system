package com.assignment.tickets.controller;

import com.assignment.tickets.dto.request.ReserveRequest;
import com.assignment.tickets.dto.response.ReservationOutcome;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.observability.ReservationMetrics;
import com.assignment.tickets.service.ReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The caller is always the token's subject; any user id in a request body is ignored. */
@RestController
@RequiredArgsConstructor
public class ReservationController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReservationService service;
    /** Wraps each service call so outcomes are counted after the transaction completes. */
    private final ReservationMetrics metrics;

    /** A replayed retry returns the original reservation with 201, flagged by a response header. */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID showId,
                                                       @AuthenticationPrincipal Jwt caller,
                                                       @Valid @RequestBody ReserveRequest request) {
        ReservationOutcome outcome = metrics.recordReserve(showId,
                () -> service.reserve(showId, caller.getSubject(), request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.reservation());
    }

    /** Owner only. Cancelling twice is safe and returns the cancelled reservation again. */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt caller) {
        return metrics.recordCancel(reservationId, () -> service.cancel(reservationId, caller.getSubject()))
                .reservation();
    }
}

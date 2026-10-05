package com.assignment.tickets.controller;

import com.assignment.tickets.dto.request.ReserveRequest;
import com.assignment.tickets.dto.response.ReservationOutcome;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.exception.BadRequestException;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The caller is always the token's subject; any user id in a request body is ignored. */
@RestController
@RequiredArgsConstructor
public class ReservationController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 128;

    private final ReservationService service;
    /** Wraps each service call so outcomes are counted after the transaction completes. */
    private final ReservationMetrics metrics;

    /**
     * The idempotency key comes from the {@code Idempotency-Key} header or the body's
     * {@code idempotency_key}. A replayed retry returns the original reservation with 201,
     * flagged by a response header.
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID showId,
                                                       @AuthenticationPrincipal Jwt caller,
                                                       @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String headerKey,
                                                       @Valid @RequestBody ReserveRequest request) {
        ReserveRequest resolved = request.withIdempotencyKey(resolveKey(headerKey, request.idempotencyKey()));
        ReservationOutcome outcome = metrics.recordReserve(showId,
                () -> service.reserve(showId, caller.getSubject(), resolved));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.reservation());
    }

    private static String resolveKey(String headerKey, String bodyKey) {
        boolean inHeader = headerKey != null && !headerKey.isBlank();
        boolean inBody = bodyKey != null && !bodyKey.isBlank();
        if (inHeader && inBody && !headerKey.equals(bodyKey)) {
            throw new BadRequestException("conflicting_idempotency_key",
                    "Idempotency-Key header and idempotency_key in the body differ");
        }
        if (!inHeader && !inBody) {
            throw new BadRequestException("missing_idempotency_key",
                    "Send an Idempotency-Key header or idempotency_key in the body");
        }
        String key = inHeader ? headerKey : bodyKey;
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException("invalid_idempotency_key",
                    "Idempotency key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    /** Owner only. Cancelling twice is safe and returns the cancelled reservation again. */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt caller) {
        return metrics.recordCancel(reservationId, () -> service.cancel(reservationId, caller.getSubject()))
                .reservation();
    }
}

package com.assignment.tickets.dto.response;

/** {@code replayed} is true when an idempotent retry returned an existing reservation. */
public record ReservationOutcome(ReservationResponse reservation, boolean replayed) {
}

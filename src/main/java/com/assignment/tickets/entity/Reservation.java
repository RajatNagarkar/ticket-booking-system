package com.assignment.tickets.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status,
        String idempotencyKey,
        String requestHash,
        Instant createdAt) {

    public static final String CONFIRMED = "confirmed";
    public static final String CANCELLED = "cancelled";
}

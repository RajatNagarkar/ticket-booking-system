package com.assignment.tickets.entity;

import java.util.UUID;

/** A seat's committed status and owner, read without locking. */
public record SeatState(String seatNo, String status, String userId, UUID reservationId) {
}

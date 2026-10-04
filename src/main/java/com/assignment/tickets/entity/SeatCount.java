package com.assignment.tickets.entity;

import java.util.UUID;

/** Number of a show's seats in one status, alongside the show's declared total. */
public record SeatCount(UUID showId, int totalSeats, String status, long count) {
}

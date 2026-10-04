package com.assignment.tickets.entity;

import java.util.UUID;

public record SeatCount(UUID showId, String status, long count) {
}

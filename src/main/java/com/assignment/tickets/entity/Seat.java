package com.assignment.tickets.entity;

public record Seat(String seatNo, String status) {

    public static final String AVAILABLE = "available";
    public static final String HELD = "held";
    public static final String CONFIRMED = "confirmed";
}

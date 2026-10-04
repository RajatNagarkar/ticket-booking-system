package com.assignment.tickets.dto.response;

import com.assignment.tickets.entity.Seat;

public record SeatResponse(String seatNo, String status) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.seatNo(), seat.status());
    }
}

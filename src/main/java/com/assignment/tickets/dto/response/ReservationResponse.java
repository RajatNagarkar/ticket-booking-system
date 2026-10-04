package com.assignment.tickets.dto.response;

import com.assignment.tickets.entity.Reservation;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.id(), reservation.showId(), reservation.userId(),
                reservation.seats(), reservation.amountPaise(), reservation.status());
    }
}

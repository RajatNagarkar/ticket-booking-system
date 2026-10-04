package com.assignment.tickets.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Also returned for another user's reservation, so ownership is not revealed. */
public class ReservationNotFoundException extends ApiException {

    public ReservationNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "reservation_not_found", "Reservation " + id + " not found");
    }
}

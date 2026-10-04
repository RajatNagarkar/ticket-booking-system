package com.assignment.tickets.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

public class SeatTakenException extends ApiException {

    public SeatTakenException(List<String> seats) {
        super(HttpStatus.CONFLICT, "seat_taken", "Seats already taken: " + String.join(", ", seats));
    }
}

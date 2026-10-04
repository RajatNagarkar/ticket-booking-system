package com.assignment.tickets.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

public class UnknownSeatException extends ApiException {

    public UnknownSeatException(List<String> seats) {
        super(HttpStatus.BAD_REQUEST, "unknown_seat", "Seats not in this show: " + String.join(", ", seats));
    }
}

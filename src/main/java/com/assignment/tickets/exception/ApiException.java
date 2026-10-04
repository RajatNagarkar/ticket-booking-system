package com.assignment.tickets.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A domain outcome that maps to a 4xx response. {@code code} is the stable,
 * machine-readable reason (e.g. {@code show_not_found}, {@code seat_taken}).
 * <p>
 * These are expected outcomes, thrown thousands of times during an on-sale burst,
 * so no stack trace is captured.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }
}

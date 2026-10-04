package com.assignment.tickets.exception;

import org.springframework.http.HttpStatus;

public class PerUserLimitException extends ApiException {

    public PerUserLimitException(int limit) {
        super(HttpStatus.CONFLICT, "per_user_limit", "At most " + limit + " seats per user for this show");
    }
}

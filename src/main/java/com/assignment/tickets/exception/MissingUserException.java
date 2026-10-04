package com.assignment.tickets.exception;

import org.springframework.http.HttpStatus;

public class MissingUserException extends ApiException {

    public MissingUserException() {
        super(HttpStatus.UNAUTHORIZED, "unauthenticated", "Missing user identity");
    }
}

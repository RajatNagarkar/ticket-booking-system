package com.assignment.tickets.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.CONFLICT, "idempotency_key_reused",
                "Idempotency key was already used with a different request");
    }
}

package com.assignment.tickets.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ShowNotFoundException extends ApiException {

    public ShowNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "show_not_found", "Show " + id + " not found");
    }
}

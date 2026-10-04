package com.assignment.tickets.controller;

import com.assignment.tickets.dto.request.ReserveRequest;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.exception.MissingUserException;
import com.assignment.tickets.service.ReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService service;

    // TODO(auth): user id comes from the JWT subject, not a header.
    @PostMapping("/shows/{showId}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@PathVariable UUID showId,
                                       @RequestHeader(name = "X-User-Id", required = false) String userId,
                                       @Valid @RequestBody ReserveRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new MissingUserException();
        }
        return service.reserve(showId, userId, request);
    }
}

package com.assignment.tickets.controller;

import com.assignment.tickets.dto.request.CreateShowRequest;
import com.assignment.tickets.dto.response.ShowResponse;
import com.assignment.tickets.service.ShowService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
@RequiredArgsConstructor
public class ShowController {

    private final ShowService service;

    // TODO(auth): admin only
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody CreateShowRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}

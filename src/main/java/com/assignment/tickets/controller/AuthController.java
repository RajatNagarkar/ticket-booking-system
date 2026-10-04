package com.assignment.tickets.controller;

import com.assignment.tickets.dto.request.TokenRequest;
import com.assignment.tickets.dto.response.TokenResponse;
import com.assignment.tickets.service.TokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dev-only token issuer, open to anyone; a real deployment would use an identity provider. */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokens;

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        return tokens.issue(request.userId(), "admin".equals(request.role()));
    }
}

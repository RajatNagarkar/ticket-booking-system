package com.assignment.tickets.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code role} is {@code user} (default) or {@code admin}. */
public record TokenRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._@-]+") String userId,
        @Pattern(regexp = "user|admin") String role) {
}

package com.assignment.tickets.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReserveRequest(
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 16) String> seats,
        @NotBlank @Size(max = 128) String idempotencyKey) {
}

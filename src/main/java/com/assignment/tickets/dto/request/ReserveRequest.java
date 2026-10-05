package com.assignment.tickets.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code idempotencyKey} may instead be sent in the {@code Idempotency-Key} header; the
 * controller resolves the two into one value before the request reaches the service.
 */
public record ReserveRequest(
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 16) String> seats,
        @Size(max = 128) String idempotencyKey) {

    public ReserveRequest withIdempotencyKey(String key) {
        return new ReserveRequest(seats, key);
    }
}

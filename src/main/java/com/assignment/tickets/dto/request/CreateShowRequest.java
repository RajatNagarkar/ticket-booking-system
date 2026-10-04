package com.assignment.tickets.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateShowRequest(
        @NotBlank @Size(max = 200) String name,
        @NotEmpty @Size(max = 10_000) List<@NotBlank @Size(max = 16) String> seats,
        @NotNull @PositiveOrZero Long pricePaise,
        @Positive Integer perUserLimit) {
}

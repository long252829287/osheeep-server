package com.osheeep.server.dinner.ingredient.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpsertInventoryItemRequest(
        @DecimalMin(value = "0.000") BigDecimal quantity,
        @NotBlank @Size(max = 16) String unit,
        @NotNull @PositiveOrZero Long version
) {
}

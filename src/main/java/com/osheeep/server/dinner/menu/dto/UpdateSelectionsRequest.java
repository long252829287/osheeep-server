package com.osheeep.server.dinner.menu.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record UpdateSelectionsRequest(
        @NotNull List<@NotNull @Positive Long> recipeIds,
        @NotNull @PositiveOrZero Long version
) {
}

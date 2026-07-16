package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RecipeIngredientInput(
        @NotNull Long ingredientId,
        @DecimalMin("0.000") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        @NotBlank @Size(max = 16) String unit,
        @NotNull Boolean required
) {
}

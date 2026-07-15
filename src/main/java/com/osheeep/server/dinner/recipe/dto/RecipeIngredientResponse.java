package com.osheeep.server.dinner.recipe.dto;

import java.math.BigDecimal;

public record RecipeIngredientResponse(
        Long ingredientId,
        String name,
        BigDecimal quantity,
        String unit,
        boolean required,
        int sortOrder
) {
}

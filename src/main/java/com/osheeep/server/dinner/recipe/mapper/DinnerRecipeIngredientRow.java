package com.osheeep.server.dinner.recipe.mapper;

import java.math.BigDecimal;

public record DinnerRecipeIngredientRow(
        Long recipeId,
        Long ingredientId,
        String name,
        BigDecimal quantity,
        String unit,
        boolean required,
        int sortOrder
) {
}

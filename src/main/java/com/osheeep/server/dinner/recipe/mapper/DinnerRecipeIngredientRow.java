package com.osheeep.server.dinner.recipe.mapper;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.AutomapConstructor;

public record DinnerRecipeIngredientRow(
        Long recipeId,
        Long ingredientId,
        String name,
        BigDecimal quantity,
        String unit,
        boolean required,
        int sortOrder,
        String ingredientScope,
        Long ingredientHouseholdId,
        String ingredientStatus
) {

    @AutomapConstructor
    public DinnerRecipeIngredientRow {
    }

    public DinnerRecipeIngredientRow(
            Long recipeId,
            Long ingredientId,
            String name,
            BigDecimal quantity,
            String unit,
            boolean required,
            int sortOrder
    ) {
        this(recipeId, ingredientId, name, quantity, unit, required, sortOrder,
                "SYSTEM", null, "ACTIVE");
    }
}

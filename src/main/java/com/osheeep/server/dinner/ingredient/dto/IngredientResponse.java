package com.osheeep.server.dinner.ingredient.dto;

public record IngredientResponse(
        Long id,
        String name,
        String category,
        String defaultUnit
) {
}

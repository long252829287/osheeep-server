package com.osheeep.server.dinner.recipe.dto;

public record RecipeMethodSummaryResponse(
        Long id,
        String name,
        String cookingStyle
) {
}

package com.osheeep.server.dinner.menu.dto;

import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;

public record MenuDishResponse(
        Long recipeId,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        String source,
        String scope,
        Long recipeVersion,
        RecipeMethodSummaryResponse method
) {
}

package com.osheeep.server.dinner.recipe.dto;

import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;

public record RecipeResponse(
        Long id,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes
) {
    public static RecipeResponse from(DinnerRecipeEntity recipe) {
        return new RecipeResponse(
                recipe.getId(), recipe.getName(), recipe.getImagePath(), recipe.getCategory(),
                recipe.getFlavor(), recipe.getEstimatedMinutes());
    }
}

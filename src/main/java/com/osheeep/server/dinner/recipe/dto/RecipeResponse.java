package com.osheeep.server.dinner.recipe.dto;

import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import java.util.List;

public record RecipeResponse(
        Long id,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        List<RecipeIngredientResponse> ingredients,
        RecipeMatchResponse match
) {
    public RecipeResponse(
            Long id,
            String name,
            String imagePath,
            String category,
            String flavor,
            Integer estimatedMinutes
    ) {
        this(id, name, imagePath, category, flavor, estimatedMinutes, List.of(), null);
    }

    public static RecipeResponse from(DinnerRecipeEntity recipe) {
        return new RecipeResponse(
                recipe.getId(), recipe.getName(), recipe.getImagePath(), recipe.getCategory(),
                recipe.getFlavor(), recipe.getEstimatedMinutes());
    }
}

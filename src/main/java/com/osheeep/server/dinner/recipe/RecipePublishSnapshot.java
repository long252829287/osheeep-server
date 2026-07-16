package com.osheeep.server.dinner.recipe;

import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import java.util.List;

public record RecipePublishSnapshot(
        Long recipeId,
        Long creatorId,
        Long householdId,
        long version,
        String name,
        String category,
        String flavor,
        Integer servings,
        Integer estimatedMinutes,
        Long imageAssetId,
        List<RecipeIngredientResponse> ingredients,
        RecipeMethodResponse defaultMethod,
        String moderationText
) {
}

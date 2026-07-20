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

    public RecipePublishSnapshot {
        ingredients = ingredients == null ? null : List.copyOf(ingredients);
        if (defaultMethod != null && defaultMethod.steps() != null) {
            defaultMethod = new RecipeMethodResponse(
                    defaultMethod.id(), defaultMethod.name(), defaultMethod.cookingStyle(),
                    List.copyOf(defaultMethod.steps()));
        }
    }
}

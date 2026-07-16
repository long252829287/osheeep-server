package com.osheeep.server.dinner.recipe.dto;

import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import java.time.Instant;
import java.util.List;

public record RecipeDraftResponse(
        Long id,
        String status,
        Long version,
        String name,
        String category,
        String flavor,
        Integer servings,
        Integer estimatedMinutes,
        List<RecipeIngredientResponse> ingredients,
        RecipeMethodResponse defaultMethod,
        ImageAssetResponse image,
        List<String> incompleteSteps,
        Instant updatedAt
) {
}

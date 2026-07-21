package com.osheeep.server.dinner.record.dto;

import java.util.List;

public record RecordDishResponse(
        Long recipeId,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        String source,
        String scope,
        Long recipeVersion,
        Integer servings,
        RecordMethodSnapshotResponse method,
        List<RecordIngredientSnapshotResponse> ingredients
) {
    public RecordDishResponse {
        ingredients = List.copyOf(ingredients);
    }
}

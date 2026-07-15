package com.osheeep.server.dinner.recipe.dto;

import java.util.List;

public record RecipeMatchResponse(
        String status,
        int matchedRequired,
        int totalRequired,
        int matchPercent,
        List<String> missingIngredients,
        List<String> unknownQuantityIngredients
) {
}

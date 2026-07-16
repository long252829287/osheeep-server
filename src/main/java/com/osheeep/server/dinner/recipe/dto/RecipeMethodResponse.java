package com.osheeep.server.dinner.recipe.dto;

import java.util.List;

public record RecipeMethodResponse(
        Long id,
        String name,
        String cookingStyle,
        List<RecipeMethodStepResponse> steps
) {
}

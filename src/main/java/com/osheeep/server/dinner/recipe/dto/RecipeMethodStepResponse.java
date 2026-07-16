package com.osheeep.server.dinner.recipe.dto;

public record RecipeMethodStepResponse(
        String instruction,
        int sortOrder
) {
}

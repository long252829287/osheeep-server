package com.osheeep.server.dinner.recipe.dto;

public record RecipeValidationIssue(
        String step,
        String field,
        String message
) {
}

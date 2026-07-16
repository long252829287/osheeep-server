package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplaceRecipeIngredientsRequest(
        @jakarta.validation.constraints.Min(1) long version,
        @NotNull @Size(max = 50) @Valid List<RecipeIngredientInput> ingredients
) {
}

package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.constraints.Size;

public record RecipeMethodStepInput(
        @Size(max = 160) String instruction
) {
}

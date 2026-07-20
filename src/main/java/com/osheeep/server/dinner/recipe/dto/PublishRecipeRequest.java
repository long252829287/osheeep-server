package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.constraints.Min;

public record PublishRecipeRequest(@Min(1) long version) {
}

package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateRecipeBasicInfoRequest(
        @Min(1) long version,
        @Size(max = 40) String name,
        @Size(max = 16) String category,
        @Size(max = 16) String flavor,
        @Min(1) @Max(20) Integer servings,
        @Min(1) @Max(1440) Integer estimatedMinutes
) {
}

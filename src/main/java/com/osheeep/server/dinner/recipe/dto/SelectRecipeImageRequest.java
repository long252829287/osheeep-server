package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.constraints.Min;

public record SelectRecipeImageRequest(
        @Min(1) long version,
        Long imageAssetId
) {
}

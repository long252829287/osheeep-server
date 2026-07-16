package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateDefaultMethodRequest(
        @Min(1) long version,
        @Size(max = 40) String name,
        @Size(max = 32) String cookingStyle,
        @NotNull @Size(max = 12) @Valid List<RecipeMethodStepInput> steps
) {
}

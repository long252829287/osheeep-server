package com.osheeep.server.dinner.recipe.dto;

import java.time.Instant;

public record FamilyRecipeListItemResponse(
        Long id,
        String status,
        String name,
        String imageUrl,
        String category,
        String flavor,
        Integer servings,
        Integer estimatedMinutes,
        Long version,
        Long creatorId,
        String creatorName,
        Long lastModifiedBy,
        String lastModifiedByName,
        String completedStep,
        Instant updatedAt
) {
}

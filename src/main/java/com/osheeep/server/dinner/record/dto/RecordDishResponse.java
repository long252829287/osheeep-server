package com.osheeep.server.dinner.record.dto;

public record RecordDishResponse(
        Long recipeId,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        String source
) {
}

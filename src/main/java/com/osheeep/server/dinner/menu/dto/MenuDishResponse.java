package com.osheeep.server.dinner.menu.dto;

public record MenuDishResponse(
        Long recipeId,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        String source
) {
}

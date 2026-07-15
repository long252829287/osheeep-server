package com.osheeep.server.dinner.ingredient.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryItemResponse(
        Long ingredientId,
        String name,
        String category,
        BigDecimal quantity,
        String unit,
        Long version,
        Long updatedBy,
        Instant updatedAt
) {
}

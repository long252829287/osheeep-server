package com.osheeep.server.dinner.record.dto;

import java.math.BigDecimal;

public record RecordIngredientSnapshotResponse(
        Long ingredientId,
        String name,
        BigDecimal quantity,
        String unit,
        boolean required,
        int sortOrder
) {
}

package com.osheeep.server.dinner.menu.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TodayMenuResponse(
        Long id,
        LocalDate menuDate,
        String status,
        Long version,
        int mySelectionCount,
        int partnerSelectionCount,
        int consensusCount,
        List<Long> selectedRecipeIds,
        List<MenuDishResponse> dishes,
        Long confirmedBy,
        Instant confirmedAt,
        Long completedBy,
        Instant completedAt,
        Long recordId
) {
}

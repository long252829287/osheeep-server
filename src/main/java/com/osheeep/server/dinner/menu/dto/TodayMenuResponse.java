package com.osheeep.server.dinner.menu.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TodayMenuResponse(
        Long id,
        LocalDate menuDate,
        String status,
        Long version,
        Integer mySelectionCount,
        Integer partnerSelectionCount,
        Integer consensusCount,
        List<Long> selectedRecipeIds,
        List<MenuDishResponse> dishes,
        Long confirmedBy,
        Instant confirmedAt,
        Long completedBy,
        Instant completedAt,
        Long recordId,
        Boolean historyVisible
) {
    public TodayMenuResponse(
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
        this(id, menuDate, status, version,
                mySelectionCount, partnerSelectionCount, consensusCount,
                selectedRecipeIds, dishes, confirmedBy, confirmedAt,
                completedBy, completedAt, recordId, true);
    }

    public static TodayMenuResponse preMembership(LocalDate menuDate) {
        return new TodayMenuResponse(
                null, menuDate, "PRE_MEMBERSHIP", null,
                null, null, null, null, null,
                null, null, null, null, null, false);
    }
}

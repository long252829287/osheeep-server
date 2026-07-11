package com.osheeep.server.dinner.record.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RecordDetailResponse(
        Long id,
        LocalDate recordDate,
        Long completedBy,
        Instant completedAt,
        List<RecordDishResponse> dishes
) {
}

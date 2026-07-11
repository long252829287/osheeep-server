package com.osheeep.server.dinner.record.dto;

import java.time.Instant;
import java.time.LocalDate;

public record RecordSummaryResponse(
        Long id,
        LocalDate recordDate,
        Long completedBy,
        Instant completedAt,
        int dishCount
) {
}

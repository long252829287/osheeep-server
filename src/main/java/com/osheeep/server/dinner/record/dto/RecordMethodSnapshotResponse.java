package com.osheeep.server.dinner.record.dto;

import java.util.List;

public record RecordMethodSnapshotResponse(
        Long id,
        String name,
        String cookingStyle,
        List<RecordMethodStepSnapshotResponse> steps
) {
    public RecordMethodSnapshotResponse {
        steps = List.copyOf(steps);
    }
}

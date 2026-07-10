package com.osheeep.server.thought.outline.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ThoughtOutlineResponse(
        Long id,
        Long userId,
        Long clusterId,
        List<String> titleCandidates,
        String coreArgument,
        List<OutlineSectionResponse> outline,
        List<Long> supportingFragmentIds,
        List<String> missingMaterials,
        LocalDateTime createdAt
) {
}

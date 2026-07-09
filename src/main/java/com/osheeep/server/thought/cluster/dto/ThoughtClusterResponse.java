package com.osheeep.server.thought.cluster.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ThoughtClusterResponse(
        Long id,
        Long userId,
        String title,
        String thesis,
        List<Long> fragmentIds,
        int maturityScore,
        List<String> missingQuestions,
        String status,
        LocalDateTime updatedAt
) {
}

package com.osheeep.server.thought.cluster.dto;

public record RebuildClustersResponse(
        String userId,
        String status,
        String note,
        String jobId
) {
}

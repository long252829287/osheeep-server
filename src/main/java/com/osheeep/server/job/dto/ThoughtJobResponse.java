package com.osheeep.server.job.dto;

public record ThoughtJobResponse(
        String userId,
        String status,
        String note,
        String jobId
) {
}

package com.osheeep.server.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.job.dto.ThoughtJobResponse;
import com.osheeep.server.job.entity.JobEntity;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_RETRYING = "retrying";

    private final JobMapper jobMapper;
    private final ObjectMapper objectMapper;

    public JobService(JobMapper jobMapper, ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JobEntity start(Long userId, String type) {
        LocalDateTime now = LocalDateTime.now();
        JobEntity job = new JobEntity();
        job.setUserId(userId);
        job.setType(type);
        job.setStatus(STATUS_RUNNING);
        job.setPayloadJson(writeJson(Map.of("userId", userId)));
        job.setScheduledAt(now);
        job.setStartedAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);
        return job;
    }

    @Transactional
    public ThoughtJobResponse complete(JobEntity job, String note, Object result) {
        LocalDateTime now = LocalDateTime.now();
        job.setStatus(STATUS_COMPLETED);
        job.setResultJson(writeJson(result));
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        jobMapper.updateById(job);
        return response(job, note);
    }

    @Transactional
    public void fail(JobEntity job, RuntimeException exception) {
        LocalDateTime now = LocalDateTime.now();
        job.setStatus(STATUS_FAILED);
        job.setErrorMessage(exception.getMessage());
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        jobMapper.updateById(job);
    }

    private ThoughtJobResponse response(JobEntity job, String note) {
        return new ThoughtJobResponse(
                job.getUserId().toString(),
                job.getStatus(),
                note,
                job.getId() == null ? null : job.getId().toString()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize job payload", exception);
        }
    }
}

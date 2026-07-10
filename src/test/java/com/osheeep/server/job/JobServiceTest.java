package com.osheeep.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.job.dto.ThoughtJobResponse;
import com.osheeep.server.job.entity.JobEntity;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class JobServiceTest {

    private JobMapper jobMapper;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobMapper = Mockito.mock(JobMapper.class);
        jobService = new JobService(jobMapper, new ObjectMapper());
    }

    @Test
    void startAndCompletePersistsCompletedJob() {
        when(jobMapper.insert(any(JobEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, JobEntity.class).setId(90L);
            return 1;
        });

        JobEntity job = jobService.start(42L, JobWorker.CLUSTER_REBUILD_QUEUE);
        ThoughtJobResponse response = jobService.complete(job, "主题聚类已重建", Map.of("clusters", 2));

        assertThat(job.getStatus()).isEqualTo(JobService.STATUS_COMPLETED);
        assertThat(job.getPayloadJson()).contains("\"userId\":42");
        assertThat(job.getResultJson()).contains("\"clusters\":2");
        assertThat(response.jobId()).isEqualTo("90");
        assertThat(response.status()).isEqualTo(JobService.STATUS_COMPLETED);

        ArgumentCaptor<JobEntity> updateCaptor = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void failPersistsFailureDetails() {
        JobEntity job = new JobEntity();
        job.setId(91L);
        job.setUserId(42L);
        job.setStatus(JobService.STATUS_RUNNING);

        jobService.fail(job, new IllegalStateException("worker failed"));

        assertThat(job.getStatus()).isEqualTo(JobService.STATUS_FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("worker failed");
        assertThat(job.getFinishedAt()).isNotNull();
        verify(jobMapper).updateById(job);
    }
}

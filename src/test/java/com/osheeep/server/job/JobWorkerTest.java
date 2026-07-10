package com.osheeep.server.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.job.dto.ThoughtJobResponse;
import com.osheeep.server.job.entity.JobEntity;
import com.osheeep.server.thought.cluster.ThoughtClusterService;
import com.osheeep.server.thought.cluster.dto.RebuildClustersResponse;
import com.osheeep.server.thought.outline.ThoughtOutlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JobWorkerTest {

    private JobService jobService;
    private ThoughtClusterService clusterService;
    private JobWorker jobWorker;

    @BeforeEach
    void setUp() {
        jobService = Mockito.mock(JobService.class);
        clusterService = Mockito.mock(ThoughtClusterService.class);
        ThoughtOutlineService outlineService = Mockito.mock(ThoughtOutlineService.class);
        jobWorker = new JobWorker(jobService, clusterService, outlineService);
    }

    @Test
    void runClusterRebuildCompletesJobAfterBusinessWork() {
        JobEntity job = job(90L);
        RebuildClustersResponse result = new RebuildClustersResponse("42", "completed", "主题聚类已重建", null);
        ThoughtJobResponse response = new ThoughtJobResponse("42", "completed", "主题聚类已重建", "90");
        when(jobService.start(42L, JobWorker.CLUSTER_REBUILD_QUEUE)).thenReturn(job);
        when(clusterService.rebuild(42L)).thenReturn(result);
        when(jobService.complete(eq(job), eq("主题聚类已重建"), eq(result))).thenReturn(response);

        jobWorker.runClusterRebuild(42L);

        verify(jobService).complete(job, "主题聚类已重建", result);
    }

    @Test
    void runClusterRebuildMarksJobFailedWhenBusinessWorkFails() {
        JobEntity job = job(91L);
        IllegalStateException failure = new IllegalStateException("cluster failed");
        when(jobService.start(42L, JobWorker.CLUSTER_REBUILD_QUEUE)).thenReturn(job);
        when(clusterService.rebuild(42L)).thenThrow(failure);

        assertThatThrownBy(() -> jobWorker.runClusterRebuild(42L)).isSameAs(failure);

        verify(jobService).fail(eq(job), any(IllegalStateException.class));
    }

    private JobEntity job(Long id) {
        JobEntity job = new JobEntity();
        job.setId(id);
        job.setUserId(42L);
        job.setStatus(JobService.STATUS_RUNNING);
        return job;
    }
}

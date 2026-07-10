package com.osheeep.server.job;

import com.osheeep.server.job.dto.ThoughtJobResponse;
import com.osheeep.server.job.entity.JobEntity;
import com.osheeep.server.thought.cluster.ThoughtClusterService;
import com.osheeep.server.thought.cluster.dto.RebuildClustersResponse;
import com.osheeep.server.thought.outline.ThoughtOutlineService;
import com.osheeep.server.thought.outline.dto.ThoughtOutlineResponse;
import org.springframework.stereotype.Component;

@Component
public class JobWorker {

    public static final String CLUSTER_REBUILD_QUEUE = "osheeep.thought.cluster.rebuild";
    public static final String OUTLINE_GENERATE_QUEUE = "osheeep.thought.outline.generate";

    private final JobService jobService;
    private final ThoughtClusterService clusterService;
    private final ThoughtOutlineService outlineService;

    public JobWorker(
            JobService jobService,
            ThoughtClusterService clusterService,
            ThoughtOutlineService outlineService
    ) {
        this.jobService = jobService;
        this.clusterService = clusterService;
        this.outlineService = outlineService;
    }

    public ThoughtJobResponse runClusterRebuild(Long userId) {
        JobEntity job = jobService.start(userId, CLUSTER_REBUILD_QUEUE);
        try {
            RebuildClustersResponse result = clusterService.rebuild(userId);
            return jobService.complete(job, "主题聚类已重建", result);
        } catch (RuntimeException exception) {
            jobService.fail(job, exception);
            throw exception;
        }
    }

    public ThoughtOutlineResponse runOutlineGeneration(Long userId) {
        JobEntity job = jobService.start(userId, OUTLINE_GENERATE_QUEUE);
        try {
            ThoughtOutlineResponse result = outlineService.generate(userId);
            jobService.complete(job, "文章大纲已生成", result);
            return result;
        } catch (RuntimeException exception) {
            jobService.fail(job, exception);
            throw exception;
        }
    }

    public ThoughtJobResponse handleClusterRebuildMessage(Long userId) {
        return runClusterRebuild(userId);
    }

    public ThoughtOutlineResponse handleOutlineGenerateMessage(Long userId) {
        return runOutlineGeneration(userId);
    }
}

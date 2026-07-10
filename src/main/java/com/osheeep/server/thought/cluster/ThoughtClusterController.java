package com.osheeep.server.thought.cluster;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.job.JobWorker;
import com.osheeep.server.job.dto.ThoughtJobResponse;
import com.osheeep.server.thought.cluster.dto.ThoughtClusterResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/thoughts/clusters")
public class ThoughtClusterController {

    private final ThoughtClusterService clusterService;
    private final JobWorker jobWorker;

    public ThoughtClusterController(ThoughtClusterService clusterService, JobWorker jobWorker) {
        this.clusterService = clusterService;
        this.jobWorker = jobWorker;
    }

    @GetMapping
    public ApiResponse<List<ThoughtClusterResponse>> list(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(clusterService.listClusters(currentUser.id()));
    }

    @PostMapping("/rebuild")
    public ApiResponse<ThoughtJobResponse> rebuild(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(jobWorker.runClusterRebuild(currentUser.id()));
    }
}

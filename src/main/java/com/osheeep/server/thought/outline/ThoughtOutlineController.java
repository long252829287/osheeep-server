package com.osheeep.server.thought.outline;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.job.JobWorker;
import com.osheeep.server.thought.outline.dto.ThoughtOutlineResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/thoughts/outlines")
public class ThoughtOutlineController {

    private final ThoughtOutlineService outlineService;
    private final JobWorker jobWorker;

    public ThoughtOutlineController(ThoughtOutlineService outlineService, JobWorker jobWorker) {
        this.outlineService = outlineService;
        this.jobWorker = jobWorker;
    }

    @PostMapping("/generate")
    public ApiResponse<ThoughtOutlineResponse> generate(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(jobWorker.runOutlineGeneration(currentUser.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ThoughtOutlineResponse> get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(outlineService.get(currentUser.id(), id));
    }
}

package com.osheeep.server.dinner.record;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.record.dto.RecordDetailResponse;
import com.osheeep.server.dinner.record.dto.RecordSummaryResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/records")
public class DinnerRecordController {

    private final DinnerRecordService recordService;

    public DinnerRecordController(DinnerRecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping
    public ApiResponse<List<RecordSummaryResponse>> list(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(recordService.list(currentUser.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecordDetailResponse> detail(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(recordService.detail(currentUser.id(), id));
    }
}

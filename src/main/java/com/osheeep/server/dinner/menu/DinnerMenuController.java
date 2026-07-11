package com.osheeep.server.dinner.menu;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.menu.dto.MenuActionRequest;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
import com.osheeep.server.dinner.menu.dto.UpdateSelectionsRequest;
import com.osheeep.server.dinner.record.DinnerRecordService;
import com.osheeep.server.dinner.record.dto.CompleteMenuResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/menus/today")
public class DinnerMenuController {

    private final DinnerMenuService menuService;
    private final DinnerRecordService recordService;

    public DinnerMenuController(DinnerMenuService menuService, DinnerRecordService recordService) {
        this.menuService = menuService;
        this.recordService = recordService;
    }

    @GetMapping
    public ApiResponse<TodayMenuResponse> today(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(menuService.today(currentUser.id()));
    }

    @PutMapping("/selections")
    public ApiResponse<TodayMenuResponse> updateSelections(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody UpdateSelectionsRequest request
    ) {
        return ApiResponse.ok(menuService.updateSelections(
                currentUser.id(), request.recipeIds(), request.version()));
    }

    @PostMapping("/confirm")
    public ApiResponse<TodayMenuResponse> confirm(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody MenuActionRequest request
    ) {
        return ApiResponse.ok(menuService.confirm(
                currentUser.id(), request.version(), request.idempotencyKey()));
    }

    @PostMapping("/complete")
    public ApiResponse<CompleteMenuResponse> complete(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody MenuActionRequest request
    ) {
        return ApiResponse.ok(recordService.complete(
                currentUser.id(), request.version(), request.idempotencyKey()));
    }
}

package com.osheeep.server.dinner.household;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.household.dto.CreateHouseholdRequest;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
import com.osheeep.server.dinner.household.dto.JoinHouseholdRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner")
public class DinnerHouseholdController {

    private final DinnerHouseholdService householdService;

    public DinnerHouseholdController(DinnerHouseholdService householdService) {
        this.householdService = householdService;
    }

    @GetMapping("/household")
    public ApiResponse<HouseholdResponse> current(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(householdService.current(currentUser.id()));
    }

    @PostMapping("/households")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<HouseholdCreatedResponse> create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateHouseholdRequest request
    ) {
        return ApiResponse.ok(householdService.create(currentUser.id(), request.name()));
    }

    @PostMapping("/households/invite-code/refresh")
    public ApiResponse<HouseholdCreatedResponse> refreshInvite(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(householdService.refreshInvite(currentUser.id()));
    }

    @PostMapping("/households/join")
    public ApiResponse<HouseholdResponse> join(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody JoinHouseholdRequest request
    ) {
        return ApiResponse.ok(householdService.join(currentUser.id(), request.inviteCode()));
    }
}

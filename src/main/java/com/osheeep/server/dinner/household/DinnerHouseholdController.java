package com.osheeep.server.dinner.household;

import com.fasterxml.jackson.databind.node.NullNode;
import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.household.dto.CreateHouseholdRequest;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdInviteStatusResponse;
import com.osheeep.server.dinner.household.dto.HouseholdManagementResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
import com.osheeep.server.dinner.household.dto.JoinHouseholdRequest;
import com.osheeep.server.dinner.household.dto.RenameHouseholdRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    public ApiResponse<?> current(@AuthenticationPrincipal CurrentUser currentUser) {
        HouseholdResponse household = householdService.current(currentUser.id());
        return ApiResponse.ok(household == null ? NullNode.getInstance() : household);
    }

    @GetMapping("/household/members")
    public ApiResponse<HouseholdManagementResponse> management(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(householdService.management(currentUser.id()));
    }

    @PutMapping("/household")
    public ApiResponse<HouseholdResponse> rename(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody RenameHouseholdRequest request
    ) {
        return ApiResponse.ok(householdService.rename(
                currentUser.id(), request.name(), request.expectedVersion()));
    }

    @GetMapping("/household/invite-code")
    public ApiResponse<HouseholdInviteStatusResponse> inviteStatus(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(householdService.inviteStatus(currentUser.id()));
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

    @PostMapping("/household/invite-code/revocation")
    public ApiResponse<HouseholdInviteStatusResponse> revokeInvite(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(householdService.revokeInvite(currentUser.id()));
    }

    @PostMapping("/households/join")
    public ApiResponse<HouseholdResponse> join(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody JoinHouseholdRequest request
    ) {
        return ApiResponse.ok(householdService.join(currentUser.id(), request.inviteCode()));
    }
}

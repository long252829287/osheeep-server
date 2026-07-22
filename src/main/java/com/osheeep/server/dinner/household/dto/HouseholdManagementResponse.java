package com.osheeep.server.dinner.household.dto;

import java.util.List;

public record HouseholdManagementResponse(
        HouseholdResponse household,
        List<HouseholdMemberResponse> members,
        HouseholdInviteStatusResponse invite
) {

    public HouseholdManagementResponse {
        members = List.copyOf(members);
    }
}

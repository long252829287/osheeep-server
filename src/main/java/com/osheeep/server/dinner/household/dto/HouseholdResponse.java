package com.osheeep.server.dinner.household.dto;

public record HouseholdResponse(
        Long id,
        String name,
        String timezone,
        int memberCount,
        Long version,
        Long inviteRevision,
        String myRole,
        Long myMembershipId,
        Long myMembershipVersion
) {

    @Override
    public String toString() {
        return "HouseholdResponse[redacted]";
    }
}

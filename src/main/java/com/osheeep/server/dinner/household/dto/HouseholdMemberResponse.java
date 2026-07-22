package com.osheeep.server.dinner.household.dto;

import java.time.Instant;

public record HouseholdMemberResponse(
        Long membershipId,
        Long version,
        String role,
        String relation,
        Instant joinedAt
) {
}

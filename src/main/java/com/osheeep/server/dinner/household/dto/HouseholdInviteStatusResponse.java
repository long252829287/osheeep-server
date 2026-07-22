package com.osheeep.server.dinner.household.dto;

import java.time.Instant;

public record HouseholdInviteStatusResponse(
        String state,
        Long inviteRevision,
        Instant expiresAt,
        boolean createdByMe
) {
}

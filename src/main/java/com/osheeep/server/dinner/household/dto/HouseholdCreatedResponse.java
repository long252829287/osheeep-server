package com.osheeep.server.dinner.household.dto;

import java.time.Instant;

public record HouseholdCreatedResponse(
        HouseholdResponse household,
        String inviteCode,
        Instant inviteExpiresAt
) {
}

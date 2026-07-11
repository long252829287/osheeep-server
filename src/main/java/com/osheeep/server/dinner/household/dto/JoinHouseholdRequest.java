package com.osheeep.server.dinner.household.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinHouseholdRequest(
        @NotBlank
        @Size(max = 32)
        String inviteCode
) {
}

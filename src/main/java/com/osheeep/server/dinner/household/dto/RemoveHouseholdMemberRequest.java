package com.osheeep.server.dinner.household.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RemoveHouseholdMemberRequest(
        @NotNull @Positive Long actorMembershipId,
        @NotNull @Positive Long expectedVersion,
        @NotNull @Positive Long targetMembershipVersion,
        @NotBlank @Size(min = 36, max = 36) String idempotencyKey
) {

    @Override
    public String toString() {
        return "RemoveHouseholdMemberRequest[redacted]";
    }
}

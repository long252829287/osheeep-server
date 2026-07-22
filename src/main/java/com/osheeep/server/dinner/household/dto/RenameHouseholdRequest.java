package com.osheeep.server.dinner.household.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RenameHouseholdRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Long expectedVersion
) {

    @Override
    public String toString() {
        return "RenameHouseholdRequest[redacted]";
    }
}

package com.osheeep.server.dinner.household.dto;

import jakarta.validation.constraints.Size;

public record CreateHouseholdRequest(@Size(max = 100) String name) {
}

package com.osheeep.server.dinner.household.dto;

public record HouseholdMutationResponse(
        String operationType,
        boolean replayed,
        boolean actorHasHousehold,
        Long householdVersion
) {
}

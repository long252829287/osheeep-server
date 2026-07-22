package com.osheeep.server.dinner.household;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DinnerHouseholdOperationRetentionScheduler {

    private final DinnerHouseholdOperationRetentionService retentionService;

    public DinnerHouseholdOperationRetentionScheduler(
            DinnerHouseholdOperationRetentionService retentionService
    ) {
        this.retentionService = retentionService;
    }

    @Scheduled(
            fixedDelayString = "${osheeep.dinner.household-operation-retention.fixed-delay-ms:3600000}",
            initialDelayString = "${osheeep.dinner.household-operation-retention.initial-delay-ms:60000}")
    public void cleanupExpiredOperations() {
        retentionService.cleanupExpired();
    }
}

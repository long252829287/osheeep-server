package com.osheeep.server.user;

import java.util.Arrays;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.StringUtils;

final class AccountDeletionTestDatabaseSafetyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String FAILURE_MESSAGE =
            "Account deletion integration test requires an explicit dedicated test database";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        var environment = applicationContext.getEnvironment();
        String selectedDatabase = environment.getProperty("OSHEEEP_DB_NAME");
        String testDatabase = environment.getProperty("OSHEEEP_DB_TEST_NAME");
        boolean localProfile = Arrays.asList(environment.getActiveProfiles()).contains("local");
        if (!localProfile
                || !StringUtils.hasText(selectedDatabase)
                || !StringUtils.hasText(testDatabase)
                || !selectedDatabase.equals(testDatabase)) {
            throw new IllegalStateException(FAILURE_MESSAGE);
        }
    }
}

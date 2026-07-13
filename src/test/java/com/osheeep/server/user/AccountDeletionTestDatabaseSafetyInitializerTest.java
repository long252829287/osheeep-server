package com.osheeep.server.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class AccountDeletionTestDatabaseSafetyInitializerTest {

    @Test
    void acceptsExplicitlySelectedDedicatedTestDatabase() {
        GenericApplicationContext context = contextWith(
                Map.of(
                        "OSHEEEP_DB_NAME", "dedicated_test_database",
                        "OSHEEEP_DB_TEST_NAME", "dedicated_test_database"),
                "local");

        assertThatCode(() -> new AccountDeletionTestDatabaseSafetyInitializer()
                        .initialize(context))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDifferentSelectedAndTestDatabaseNames() {
        GenericApplicationContext context = contextWith(
                Map.of(
                        "OSHEEEP_DB_NAME", "selected_database",
                        "OSHEEEP_DB_TEST_NAME", "dedicated_test_database"),
                "local");

        assertRejected(context);
    }

    @Test
    void rejectsBlankSelectedDatabaseName() {
        GenericApplicationContext context = contextWith(
                Map.of(
                        "OSHEEEP_DB_NAME", " ",
                        "OSHEEEP_DB_TEST_NAME", "dedicated_test_database"),
                "local");

        assertRejected(context);
    }

    @Test
    void rejectsBlankDedicatedTestDatabaseName() {
        GenericApplicationContext context = contextWith(
                Map.of(
                        "OSHEEEP_DB_NAME", "selected_database",
                        "OSHEEEP_DB_TEST_NAME", " "),
                "local");

        assertRejected(context);
    }

    @Test
    void rejectsNonLocalProfile() {
        GenericApplicationContext context = contextWith(
                Map.of(
                        "OSHEEEP_DB_NAME", "dedicated_test_database",
                        "OSHEEEP_DB_TEST_NAME", "dedicated_test_database"),
                "prod");

        assertRejected(context);
    }

    private GenericApplicationContext contextWith(
            Map<String, Object> properties,
            String activeProfile
    ) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("account-deletion-test-safety", properties));
        context.getEnvironment().setActiveProfiles(activeProfile);
        return context;
    }

    private void assertRejected(GenericApplicationContext context) {
        assertThatThrownBy(() -> new AccountDeletionTestDatabaseSafetyInitializer()
                        .initialize(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AccountDeletionTestDatabaseSafetyInitializer.FAILURE_MESSAGE);
    }
}

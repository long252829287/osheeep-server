package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class DinnerCustomRecipeTestDatabaseSafetyInitializerTest {

    private static final String TEST_DATABASE = "dedicated_test_database";
    private static final String SAFE_URL =
            "jdbc:mysql://127.0.0.1:3306/dedicated%5Ftest%5Fdatabase"
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai";

    private final DinnerCustomRecipeTestDatabaseSafetyInitializer initializer =
            new DinnerCustomRecipeTestDatabaseSafetyInitializer();

    @Test
    void rejectsAnyProfileExceptLocal() {
        try (GenericApplicationContext context = context(
                "test", TEST_DATABASE, TEST_DATABASE, SAFE_URL, null)) {
            assertRejected(context);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "'', dedicated_test_database",
            "dedicated_test_database, ''",
            "selected_database, dedicated_test_database"
    })
    void rejectsBlankOrMismatchedSelectedAndTestDatabaseNames(
            String selectedDatabase,
            String testDatabase
    ) {
        try (GenericApplicationContext context = context(
                "local", selectedDatabase, testDatabase, SAFE_URL, null)) {
            assertRejected(context);
        }
    }

    @Test
    void rejectsDatasourceOverrideWhoseParsedCatalogIsNotTheTestDatabase() {
        try (GenericApplicationContext context = context(
                "local",
                TEST_DATABASE,
                TEST_DATABASE,
                "jdbc:mysql://127.0.0.1:3306/dedicated_test_database_shadow"
                        + "?useUnicode=true&characterEncoding=utf8",
                null)) {
            assertRejected(context);
        }
    }

    @Test
    void rejectsSeparateFlywayUrlBeforeItCanSelectAnotherCatalog() {
        try (GenericApplicationContext context = context(
                "local",
                TEST_DATABASE,
                TEST_DATABASE,
                SAFE_URL,
                "jdbc:mysql://127.0.0.1:3306/shared_database?useUnicode=true")) {
            assertRejected(context);
        }
    }

    @Test
    void rejectsEvenMatchingSeparateFlywayUrlToKeepOneVerifiedConnectionSource() {
        try (GenericApplicationContext context = context(
                "local", TEST_DATABASE, TEST_DATABASE, SAFE_URL, SAFE_URL)) {
            assertRejected(context);
        }
    }

    @Test
    void acceptsExactPercentDecodedDatasourceCatalogWithQueryParameters() {
        try (GenericApplicationContext context = context(
                "local", TEST_DATABASE, TEST_DATABASE, SAFE_URL, null)) {
            assertThatCode(() -> initializer.initialize(context)).doesNotThrowAnyException();
        }
    }

    private void assertRejected(GenericApplicationContext context) {
        assertThatThrownBy(() -> initializer.initialize(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DinnerCustomRecipeTestDatabaseSafetyInitializer.FAILURE_MESSAGE);
    }

    private GenericApplicationContext context(
            String profile,
            String selectedDatabase,
            String testDatabase,
            String datasourceUrl,
            String flywayUrl
    ) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("OSHEEEP_DB_NAME", selectedDatabase);
        environment.setProperty("OSHEEEP_DB_TEST_NAME", testDatabase);
        environment.setProperty("spring.datasource.url", datasourceUrl);
        if (flywayUrl != null) {
            environment.setProperty("spring.flyway.url", flywayUrl);
        }
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        return context;
    }
}

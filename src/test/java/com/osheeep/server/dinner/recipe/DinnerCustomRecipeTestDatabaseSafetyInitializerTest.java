package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class DinnerCustomRecipeTestDatabaseSafetyInitializerTest {

    private static final String TEST_DATABASE = "dedicated_test_database";
    private static final String SAFE_URL =
            "jdbc:mysql://127.0.0.1:3306/dedicated%5Ftest%5Fdatabase"
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai";

    private final DinnerCustomRecipeTestDatabaseSafetyInitializer initializer =
            new DinnerCustomRecipeTestDatabaseSafetyInitializer(
                    TEST_DATABASE,
                    TEST_DATABASE);

    @Test
    void connectorJDbnamePropertyOverridesTheUrlPathCatalog() throws Exception {
        Class<?> connectionUrlType = Class.forName("com.mysql.cj.conf.ConnectionUrl");
        Object connectionUrl = connectionUrlType
                .getMethod("getConnectionUrlInstance", String.class, Properties.class)
                .invoke(
                        null,
                        "jdbc:mysql://127.0.0.1:3306/dedicated_test_database"
                                + "?dbname=production",
                        new Properties());

        assertThat(connectionUrlType.getMethod("getDatabase").invoke(connectionUrl))
                .isEqualTo("production");
    }

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

    @ParameterizedTest
    @CsvSource({
            "'', dedicated_test_database",
            "dedicated_test_database, ''",
            "production, dedicated_test_database",
            "dedicated_test_database, production"
    })
    void rejectsBlankOrMismatchedOriginalEnvironmentDatabaseNames(
            String environmentSelectedDatabase,
            String environmentTestDatabase
    ) {
        DinnerCustomRecipeTestDatabaseSafetyInitializer environmentBoundInitializer =
                new DinnerCustomRecipeTestDatabaseSafetyInitializer(
                        environmentSelectedDatabase,
                        environmentTestDatabase);
        try (GenericApplicationContext context = context(
                "local", TEST_DATABASE, TEST_DATABASE, SAFE_URL, null)) {
            assertThatThrownBy(() -> environmentBoundInitializer.initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(DinnerCustomRecipeTestDatabaseSafetyInitializer.FAILURE_MESSAGE);
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
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void rejectsHighPrioritySystemPropertiesThatMasqueradeAsProduction() {
        String originalSelectedProperty = System.getProperty("OSHEEEP_DB_NAME");
        String originalTestProperty = System.getProperty("OSHEEEP_DB_TEST_NAME");
        String originalSelectedEnvironment = System.getenv("OSHEEEP_DB_NAME");
        String originalTestEnvironment = System.getenv("OSHEEEP_DB_TEST_NAME");
        String spoofDatabase = spoofDatabaseName(
                originalSelectedEnvironment,
                originalTestEnvironment);
        try {
            System.setProperty("OSHEEEP_DB_NAME", spoofDatabase);
            System.setProperty("OSHEEEP_DB_TEST_NAME", spoofDatabase);

            StandardEnvironment environment = new StandardEnvironment();
            environment.setActiveProfiles("local");
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "redirectedDatasource",
                    Map.of(
                            "spring.datasource.url",
                            "jdbc:mysql://127.0.0.1:3306/" + spoofDatabase)));
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                context.setEnvironment(environment);
                assertThat(environment.getProperty("OSHEEEP_DB_NAME"))
                        .isEqualTo(spoofDatabase);
                assertThat(environment.getProperty("OSHEEEP_DB_TEST_NAME"))
                        .isEqualTo(spoofDatabase);
                assertThat(System.getenv("OSHEEEP_DB_NAME"))
                        .isEqualTo(originalSelectedEnvironment);
                assertThat(System.getenv("OSHEEEP_DB_TEST_NAME"))
                        .isEqualTo(originalTestEnvironment);
                assertRejected(
                        new DinnerCustomRecipeTestDatabaseSafetyInitializer(),
                        context);
            }
        } finally {
            restoreSystemProperty("OSHEEEP_DB_NAME", originalSelectedProperty);
            restoreSystemProperty("OSHEEEP_DB_TEST_NAME", originalTestProperty);
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
        assertRejected(initializer, context);
    }

    private void assertRejected(
            DinnerCustomRecipeTestDatabaseSafetyInitializer initializerUnderTest,
            GenericApplicationContext context
    ) {
        assertThatThrownBy(() -> initializerUnderTest.initialize(context))
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

    private void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private String spoofDatabaseName(String... rawDatabaseNames) {
        String spoof = "production";
        while (java.util.Arrays.asList(rawDatabaseNames).contains(spoof)) {
            spoof += "_redirected";
        }
        return spoof;
    }
}

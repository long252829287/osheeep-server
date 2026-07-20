package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DinnerCustomRecipeFlywayMigrationStrategyTest {

    private static final String TEST_DATABASE = "dedicated_test_database";

    @Test
    void redirectedActualCatalogFailsBeforeFlywayMigrate() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog("production");
        DinnerCustomRecipeFlywayMigrationStrategy strategy =
                new DinnerCustomRecipeFlywayMigrationStrategy(TEST_DATABASE);

        assertThatThrownBy(() -> strategy.migrate(fixture.flyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DinnerCustomRecipeFlywayMigrationStrategy.FAILURE_MESSAGE);

        verify(fixture.flyway(), never()).migrate();
    }

    @Test
    void exactActualCatalogMigratesOnce() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog(TEST_DATABASE);
        DinnerCustomRecipeFlywayMigrationStrategy strategy =
                new DinnerCustomRecipeFlywayMigrationStrategy(TEST_DATABASE);

        assertThatCode(() -> strategy.migrate(fixture.flyway())).doesNotThrowAnyException();

        verify(fixture.flyway(), times(1)).migrate();
    }

    @Test
    void redirectedEffectiveFlywaySchemaFailsBeforeMigrate() throws Exception {
        FlywayFixture fixture = flywayUsingCatalog(TEST_DATABASE);
        when(fixture.configuration().getDefaultSchema()).thenReturn("production");
        when(fixture.configuration().getSchemas()).thenReturn(new String[] {"production"});
        DinnerCustomRecipeFlywayMigrationStrategy strategy =
                new DinnerCustomRecipeFlywayMigrationStrategy(TEST_DATABASE);

        assertThatThrownBy(() -> strategy.migrate(fixture.flyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DinnerCustomRecipeFlywayMigrationStrategy.FAILURE_MESSAGE);

        verify(fixture.flyway(), never()).migrate();
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void systemPropertyMasqueradeIsRejectedBeforeFlywayMigrate() throws Exception {
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
            FlywayFixture fixture = flywayUsingCatalog(spoofDatabase);

            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext()) {
                context.register(DinnerCustomRecipeMySqlIT.FlywaySafetyConfiguration.class);
                context.refresh();
                FlywayMigrationStrategy strategy =
                        context.getBean(FlywayMigrationStrategy.class);

                assertThat(context.getEnvironment().getProperty("OSHEEEP_DB_NAME"))
                        .isEqualTo(spoofDatabase);
                assertThat(context.getEnvironment().getProperty("OSHEEEP_DB_TEST_NAME"))
                        .isEqualTo(spoofDatabase);
                assertThat(System.getenv("OSHEEEP_DB_NAME"))
                        .isEqualTo(originalSelectedEnvironment);
                assertThat(System.getenv("OSHEEEP_DB_TEST_NAME"))
                        .isEqualTo(originalTestEnvironment);
                assertThatThrownBy(() -> strategy.migrate(fixture.flyway()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(DinnerCustomRecipeFlywayMigrationStrategy.FAILURE_MESSAGE);
                verify(fixture.flyway(), never()).migrate();
            }
        } finally {
            restoreSystemProperty("OSHEEEP_DB_NAME", originalSelectedProperty);
            restoreSystemProperty("OSHEEEP_DB_TEST_NAME", originalTestProperty);
        }
    }

    private FlywayFixture flywayUsingCatalog(String actualCatalog) throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(configuration.getSchemas()).thenReturn(new String[0]);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE()"))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn(actualCatalog);
        return new FlywayFixture(flyway, configuration);
    }

    private record FlywayFixture(Flyway flyway, Configuration configuration) {
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

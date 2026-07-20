package com.osheeep.server.dinner.recipe;

import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.util.StringUtils;

public final class DinnerCustomRecipeFlywayMigrationStrategy
        implements FlywayMigrationStrategy {

    static final String FAILURE_MESSAGE =
            "Dinner custom recipe Flyway migration requires the dedicated test database";

    private final String expectedDatabase;

    public DinnerCustomRecipeFlywayMigrationStrategy(String expectedDatabase) {
        this.expectedDatabase = expectedDatabase;
    }

    @Override
    public void migrate(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        if (!StringUtils.hasText(expectedDatabase)
                || configuration == null
                || !safeConfiguredSchemas(configuration)) {
            throw unsafeDatabase();
        }

        DataSource dataSource = configuration.getDataSource();
        if (dataSource == null || !Objects.equals(expectedDatabase, currentCatalog(dataSource))) {
            throw unsafeDatabase();
        }
        flyway.migrate();
    }

    private String currentCatalog(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT DATABASE()")) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException exception) {
            throw unsafeDatabase(exception);
        }
    }

    private boolean safeConfiguredSchemas(Configuration configuration) {
        String defaultSchema = configuration.getDefaultSchema();
        if (StringUtils.hasText(defaultSchema)
                && !Objects.equals(expectedDatabase, defaultSchema)) {
            return false;
        }
        String[] schemas = configuration.getSchemas();
        if (schemas == null) {
            return true;
        }
        for (String schema : schemas) {
            if (StringUtils.hasText(schema)
                    && !Objects.equals(expectedDatabase, schema)) {
                return false;
            }
        }
        return true;
    }

    private IllegalStateException unsafeDatabase() {
        return new IllegalStateException(FAILURE_MESSAGE);
    }

    private IllegalStateException unsafeDatabase(Exception cause) {
        return new IllegalStateException(FAILURE_MESSAGE, cause);
    }
}

package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class DinnerHouseholdRecipeMenuMigrationMySqlIT {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Test
    void migratesFreshV4AndV6CatalogsThroughV7() {
        String jdbcUrl = effectiveJdbcUrl();
        DataSource baseDataSource = new DriverManagerDataSource(
                jdbcUrl,
                System.getenv("OSHEEEP_DB_USERNAME"),
                System.getenv("OSHEEEP_DB_PASSWORD"));

        try (DinnerEphemeralCatalogHarness harness =
                DinnerEphemeralCatalogHarness.fromEnvironment(baseDataSource, jdbcUrl)) {
            harness.createCatalog(harness.freshCatalog());
            harness.createCatalog(harness.v4Catalog());
            harness.createCatalog(harness.v6Catalog());

            migrateFresh(harness);
            migrateProductionShapedV4(harness);
            migrateCurrentV6(harness);
        }
    }

    private void migrateFresh(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.freshCatalog();
        DataSource dataSource = harness.dataSourceFor(catalog);

        migrate(harness, dataSource, catalog, null);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertLatestSuccessfulVersion(jdbcTemplate, "7");
        assertV7Schema(jdbcTemplate, catalog);
    }

    private void migrateProductionShapedV4(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v4Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("4"));
        assertLatestSuccessfulVersion(jdbcTemplate, "4");
        LegacyFixture fixture = insertV4Fixture(jdbcTemplate);

        migrate(harness, dataSource, catalog, null);

        assertLatestSuccessfulVersion(jdbcTemplate, "7");
        assertV7Schema(jdbcTemplate, catalog);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT recipe_version FROM dinner_menu_selections WHERE id = ?",
                        Long.class,
                        fixture.selectionId()))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT method_id IS NULL FROM dinner_menu_selections WHERE id = ?",
                        Boolean.class,
                        fixture.selectionId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT recipe_scope IS NULL AND recipe_version IS NULL "
                                + "AND servings IS NULL AND method_id IS NULL "
                                + "AND method_name IS NULL AND cooking_style IS NULL "
                                + "AND method_steps IS NULL AND ingredients IS NULL "
                                + "FROM dinner_record_dish_snapshots WHERE id = ?",
                        Boolean.class,
                        fixture.snapshotId()))
                .isTrue();
    }

    private void migrateCurrentV6(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v6Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("6"));
        assertLatestSuccessfulVersion(jdbcTemplate, "6");

        migrate(harness, dataSource, catalog, null);

        assertLatestSuccessfulVersion(jdbcTemplate, "7");
        assertV7Schema(jdbcTemplate, catalog);
    }

    private void migrate(
            DinnerEphemeralCatalogHarness harness,
            DataSource dataSource,
            String catalog,
            MigrationVersion target
    ) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .defaultSchema(catalog)
                .schemas(catalog)
                .createSchemas(false);
        if (target != null) {
            configuration.target(target);
        }
        Flyway flyway = configuration.load();
        Configuration effective = flyway.getConfiguration();
        assertThat(effective.getDefaultSchema()).isEqualTo(catalog);
        assertThat(effective.getSchemas()).containsExactly(catalog);
        assertThat(effective.isCreateSchemas()).isFalse();
        harness.requireActiveCatalog(effective.getDataSource(), catalog);

        flyway.migrate();
    }

    private LegacyFixture insertV4Fixture(JdbcTemplate jdbcTemplate) {
        long userId = 900_001L;
        long householdId = 900_002L;
        long menuId = 900_003L;
        long selectionId = 900_004L;
        long recordId = 900_005L;
        long snapshotId = 900_006L;
        Long systemRecipeId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM dinner_recipes WHERE scope = 'SYSTEM'",
                Long.class);
        assertThat(systemRecipeId).isNotNull();

        jdbcTemplate.update(
                "INSERT INTO users (id, username, display_name, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE')",
                userId,
                "migration-v4-user",
                "V4 迁移用户");
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) VALUES (?, ?, ?)",
                householdId,
                "migration-v4-household",
                userId);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members (household_id, user_id) VALUES (?, ?)",
                householdId,
                userId);
        jdbcTemplate.update(
                "INSERT INTO dinner_menus "
                        + "(id, household_id, menu_date, status, version, "
                        + "confirmed_by, confirmed_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', 1, ?, ?)",
                menuId,
                householdId,
                LocalDate.of(2026, 7, 20),
                userId,
                LocalDateTime.of(2026, 7, 20, 10, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_menu_selections "
                        + "(id, menu_id, user_id, recipe_id) VALUES (?, ?, ?, ?)",
                selectionId,
                menuId,
                userId,
                systemRecipeId);
        jdbcTemplate.update(
                "INSERT INTO dinner_cooking_records "
                        + "(id, household_id, menu_id, record_date, completed_by, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                recordId,
                householdId,
                menuId,
                LocalDate.of(2026, 7, 20),
                userId,
                LocalDateTime.of(2026, 7, 20, 11, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_record_dish_snapshots "
                        + "(id, record_id, recipe_id, name, image_path, category, flavor, "
                        + "estimated_minutes, selected_by_user_ids, sort_order) "
                        + "SELECT ?, ?, id, name, image_path, category, flavor, "
                        + "estimated_minutes, ?, 0 FROM dinner_recipes WHERE id = ?",
                snapshotId,
                recordId,
                "[" + userId + "]",
                systemRecipeId);
        return new LegacyFixture(selectionId, snapshotId);
    }

    private void assertV7Schema(JdbcTemplate jdbcTemplate, String catalog) {
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_menu_selections",
                "recipe_version",
                "bigint|NO|1");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_menu_selections",
                "method_id",
                "bigint|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "recipe_scope",
                "varchar(16)|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "recipe_version",
                "bigint|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "servings",
                "int|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "method_id",
                "bigint|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "method_name",
                "varchar(64)|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "cooking_style",
                "varchar(32)|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "method_steps",
                "json|YES|<null>");
        assertColumn(
                jdbcTemplate,
                catalog,
                "dinner_record_dish_snapshots",
                "ingredients",
                "json|YES|<null>");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_menu_selections' "
                                + "AND COLUMN_NAME = 'method_id' "
                                + "AND CONSTRAINT_NAME = 'fk_dinner_selections_method' "
                                + "AND REFERENCED_TABLE_NAME = 'dinner_recipe_methods' "
                                + "AND REFERENCED_COLUMN_NAME = 'id'",
                        Integer.class,
                        catalog))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_menu_selections' "
                                + "AND INDEX_NAME = 'idx_dinner_selections_method' "
                                + "AND COLUMN_NAME = 'method_id'",
                        Integer.class,
                        catalog))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_record_dish_snapshots' "
                                + "AND COLUMN_NAME = 'method_id' "
                                + "AND REFERENCED_TABLE_NAME IS NOT NULL",
                        Integer.class,
                        catalog))
                .isZero();
    }

    private void assertColumn(
            JdbcTemplate jdbcTemplate,
            String catalog,
            String table,
            String column,
            String expected
    ) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(COLUMN_TYPE, '|', IS_NULLABLE, '|', "
                                + "COALESCE(COLUMN_DEFAULT, '<null>')) "
                                + "FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                        String.class,
                        catalog,
                        table,
                        column))
                .isEqualTo(expected);
    }

    private void assertLatestSuccessfulVersion(JdbcTemplate jdbcTemplate, String version) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
                        String.class))
                .isEqualTo(version);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0",
                        Integer.class))
                .isZero();
    }

    private String effectiveJdbcUrl() {
        String host = System.getenv("OSHEEEP_DB_HOST");
        if (host != null && host.contains(":")
                && !(host.startsWith("[") && host.endsWith("]"))) {
            host = "[" + host + "]";
        }
        return "jdbc:mysql://"
                + host
                + ":"
                + System.getenv("OSHEEEP_DB_PORT")
                + "/"
                + System.getenv("OSHEEEP_DB_NAME")
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai";
    }

    private record LegacyFixture(long selectionId, long snapshotId) {
    }
}

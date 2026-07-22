package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Early MySQL 8 smoke for the V8 household-management migration.
 *
 * <p>The file-local harness follows the existing dinner migration harness's safety rules. It rejects
 * missing opt-in, non-loopback hosts, shared catalogs, and catalog switches outside the current
 * disposable run before any destructive statement can execute.
 */
public class DinnerHouseholdManagementMigrationSmokeMySqlIT {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final LocalDateTime HISTORY_VISIBLE_FROM =
            LocalDateTime.of(1970, 1, 1, 0, 0);

    @Test
    void migratesFreshAndMinimalV7CatalogsThroughV8() {
        String jdbcUrl = effectiveJdbcUrl();
        DataSource baseDataSource = new DriverManagerDataSource(
                jdbcUrl,
                System.getenv("OSHEEEP_DB_USERNAME"),
                System.getenv("OSHEEEP_DB_PASSWORD"));

        try (V8SmokeCatalogHarness harness =
                V8SmokeCatalogHarness.fromEnvironment(baseDataSource, jdbcUrl)) {
            harness.createCatalog(harness.freshCatalog());
            harness.createCatalog(harness.v7Catalog());

            migrateFresh(harness);
            migrateMinimalV7(harness);
        }
    }

    private void migrateFresh(V8SmokeCatalogHarness harness) {
        String catalog = harness.freshCatalog();
        DataSource dataSource = harness.dataSourceFor(catalog);

        migrate(harness, dataSource, catalog, null);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertLatestSuccessfulVersion(jdbcTemplate, "8");
        assertV8Schema(jdbcTemplate, catalog);

        FreshFixture fixture = insertFreshV8Fixture(jdbcTemplate);
        assertFreshFixture(jdbcTemplate, fixture);
        assertGeneratedAndUniqueBoundaries(jdbcTemplate, fixture);
        assertCheckBoundaries(jdbcTemplate, fixture);
        assertOperationForeignKeyBoundary(jdbcTemplate, catalog, fixture);
    }

    private void migrateMinimalV7(V8SmokeCatalogHarness harness) {
        String catalog = harness.v7Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("7"));
        assertLatestSuccessfulVersion(jdbcTemplate, "7");
        LegacyFixture fixture = insertV7Fixture(jdbcTemplate);

        migrate(harness, dataSource, catalog, null);

        assertLatestSuccessfulVersion(jdbcTemplate, "8");
        assertV8Schema(jdbcTemplate, catalog);
        assertLegacyBackfill(jdbcTemplate, fixture);
    }

    private void migrate(
            V8SmokeCatalogHarness harness,
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

    private void assertV8Schema(JdbcTemplate jdbcTemplate, String catalog) {
        assertGeneratedColumn(
                jdbcTemplate,
                catalog,
                "dinner_household_members",
                "active_user_id");
        assertGeneratedColumn(
                jdbcTemplate,
                catalog,
                "dinner_household_members",
                "active_owner_household_id");
        assertGeneratedColumn(
                jdbcTemplate,
                catalog,
                "dinner_household_members",
                "active_seat_no");
        assertGeneratedColumn(
                jdbcTemplate,
                catalog,
                "dinner_invite_codes",
                "open_household_id");

        assertUniqueIndex(
                jdbcTemplate,
                catalog,
                "dinner_household_members",
                List.of("active_user_id"));
        assertUniqueIndex(
                jdbcTemplate,
                catalog,
                "dinner_household_members",
                List.of("active_owner_household_id"));
        assertUniqueIndex(
                jdbcTemplate,
                catalog,
                "dinner_household_members",
                List.of("household_id", "active_seat_no"));
        assertUniqueIndex(
                jdbcTemplate,
                catalog,
                "dinner_invite_codes",
                List.of("open_household_id"));
        assertUniqueIndex(
                jdbcTemplate,
                catalog,
                "dinner_household_operations",
                List.of("actor_id", "idempotency_key"));

        for (String table : List.of(
                "dinner_households",
                "dinner_household_members",
                "dinner_invite_codes",
                "dinner_household_operations")) {
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                                    + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = ? "
                                    + "AND CONSTRAINT_TYPE = 'CHECK' AND ENFORCED = 'YES'",
                            Integer.class,
                            catalog,
                            table))
                    .as("%s should retain enforced V8 CHECK constraints", table)
                    .isPositive();
        }
    }

    private FreshFixture insertFreshV8Fixture(JdbcTemplate jdbcTemplate) {
        long ownerUserId = 910_001L;
        long memberUserId = 910_002L;
        long formerUserId = 910_003L;
        long otherOwnerUserId = 910_004L;
        long householdId = 910_101L;
        long otherHouseholdId = 910_102L;
        long ownerMembershipId = 910_201L;
        long memberMembershipId = 910_202L;
        long formerMembershipId = 910_203L;
        long operationId = 910_301L;
        long inviteId = 910_401L;

        insertUser(jdbcTemplate, ownerUserId, "v8-fresh-owner");
        insertUser(jdbcTemplate, memberUserId, "v8-fresh-member");
        insertUser(jdbcTemplate, formerUserId, "v8-fresh-former");
        insertUser(jdbcTemplate, otherOwnerUserId, "v8-fresh-other-owner");
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) VALUES (?, ?, ?)",
                householdId,
                "V8 fresh household",
                ownerUserId);
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) VALUES (?, ?, ?)",
                otherHouseholdId,
                "V8 other household",
                otherOwnerUserId);

        LocalDateTime joinedAt = LocalDateTime.of(2026, 7, 22, 8, 0);
        insertActiveMember(
                jdbcTemplate,
                ownerMembershipId,
                householdId,
                ownerUserId,
                "OWNER",
                1,
                joinedAt);
        insertActiveMember(
                jdbcTemplate,
                memberMembershipId,
                householdId,
                memberUserId,
                "MEMBER",
                2,
                joinedAt.plusMinutes(1));
        insertActiveMember(
                jdbcTemplate,
                910_204L,
                otherHouseholdId,
                otherOwnerUserId,
                "OWNER",
                1,
                joinedAt.plusMinutes(2));
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at, role, status, seat_no, "
                        + "history_visible_from, ended_at, ended_by, end_reason) "
                        + "VALUES (?, ?, ?, ?, 'MEMBER', 'LEFT', 2, ?, ?, ?, 'SELF_LEFT')",
                formerMembershipId,
                householdId,
                formerUserId,
                joinedAt.minusDays(2),
                joinedAt.minusDays(2),
                joinedAt.minusDays(1),
                formerUserId);

        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?)",
                inviteId,
                householdId,
                "a".repeat(64),
                joinedAt.plusDays(1),
                ownerUserId);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(id, household_id, actor_id, actor_membership_id, target_member_id, "
                        + "operation_type, idempotency_key, request_fingerprint, "
                        + "result_schema_version, result_household_version, result_payload, "
                        + "created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'OWNER_REMOVE', ?, ?, 1, 1, ?, ?, ?)",
                operationId,
                householdId,
                ownerUserId,
                ownerMembershipId,
                memberMembershipId,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "b".repeat(64),
                "{\"actorHasHousehold\":true}",
                joinedAt,
                joinedAt.plusDays(14));

        return new FreshFixture(
                householdId,
                otherHouseholdId,
                ownerUserId,
                memberUserId,
                formerUserId,
                ownerMembershipId,
                memberMembershipId,
                formerMembershipId,
                operationId,
                inviteId,
                joinedAt);
    }

    private void assertFreshFixture(JdbcTemplate jdbcTemplate, FreshFixture fixture) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(role, '|', status, '|', seat_no, '|', "
                                + "active_user_id, '|', active_owner_household_id, '|', "
                                + "active_seat_no) FROM dinner_household_members WHERE id = ?",
                        String.class,
                        fixture.ownerMembershipId()))
                .isEqualTo("OWNER|ACTIVE|1|"
                        + fixture.ownerUserId()
                        + "|"
                        + fixture.householdId()
                        + "|1");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(role, '|', status, '|', seat_no, '|', "
                                + "active_user_id, '|', active_seat_no) "
                                + "FROM dinner_household_members WHERE id = ?",
                        String.class,
                        fixture.memberMembershipId()))
                .isEqualTo("MEMBER|ACTIVE|2|" + fixture.memberUserId() + "|2");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT active_user_id IS NULL "
                                + "AND active_owner_household_id IS NULL "
                                + "AND active_seat_no IS NULL "
                                + "AND ended_at IS NOT NULL "
                                + "AND ended_by IS NOT NULL "
                                + "FROM dinner_household_members WHERE id = ?",
                        Boolean.class,
                        fixture.formerMembershipId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT open_household_id FROM dinner_invite_codes WHERE id = ?",
                        Long.class,
                        fixture.inviteId()))
                .isEqualTo(fixture.householdId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT TIMESTAMPDIFF(DAY, created_at, expires_at) "
                                + "FROM dinner_household_operations WHERE id = ?",
                        Integer.class,
                        fixture.operationId()))
                .isEqualTo(14);
    }

    private void assertGeneratedAndUniqueBoundaries(
            JdbcTemplate jdbcTemplate,
            FreshFixture fixture
    ) {
        assertIntegrityViolation(() -> insertActiveMember(
                jdbcTemplate,
                910_211L,
                fixture.otherHouseholdId(),
                fixture.memberUserId(),
                "MEMBER",
                2,
                fixture.joinedAt().plusHours(1)));
        assertIntegrityViolation(() -> insertActiveMember(
                jdbcTemplate,
                910_212L,
                fixture.householdId(),
                fixture.formerUserId(),
                "OWNER",
                2,
                fixture.joinedAt().plusHours(2)));
        assertIntegrityViolation(() -> insertActiveMember(
                jdbcTemplate,
                910_213L,
                fixture.householdId(),
                fixture.formerUserId(),
                "MEMBER",
                1,
                fixture.joinedAt().plusHours(3)));

        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?)",
                910_402L,
                fixture.householdId(),
                "c".repeat(64),
                fixture.joinedAt().plusDays(1),
                fixture.ownerUserId()));
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, revoked_at, "
                        + "revocation_reason, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'REFRESHED', ?)",
                910_403L,
                fixture.householdId(),
                "d".repeat(64),
                fixture.joinedAt().plusDays(1),
                fixture.joinedAt(),
                fixture.ownerUserId());

        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(household_id, actor_id, actor_membership_id, target_member_id, "
                        + "operation_type, idempotency_key, request_fingerprint, "
                        + "result_schema_version, result_payload, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, 'OWNER_REMOVE', ?, ?, 1, ?, ?, ?)",
                fixture.householdId(),
                fixture.ownerUserId(),
                fixture.ownerMembershipId(),
                fixture.memberMembershipId(),
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "e".repeat(64),
                "{\"actorHasHousehold\":true}",
                fixture.joinedAt(),
                fixture.joinedAt().plusDays(14)));
    }

    private void assertCheckBoundaries(JdbcTemplate jdbcTemplate, FreshFixture fixture) {
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "UPDATE dinner_households SET version = 0 WHERE id = ?",
                fixture.householdId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "UPDATE dinner_households SET status = 'active' WHERE id = ?",
                fixture.householdId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "UPDATE dinner_household_members SET role = 'owner' WHERE id = ?",
                fixture.ownerMembershipId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "UPDATE dinner_household_members SET status = 'active' WHERE id = ?",
                fixture.memberMembershipId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "UPDATE dinner_household_members SET end_reason = 'self_left' WHERE id = ?",
                fixture.formerMembershipId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at, role, status, seat_no, "
                        + "history_visible_from) "
                        + "VALUES (?, ?, ?, ?, 'ADMIN', 'ACTIVE', 2, ?)",
                910_221L,
                fixture.otherHouseholdId(),
                fixture.formerUserId(),
                fixture.joinedAt(),
                fixture.joinedAt()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, revoked_at, "
                        + "revocation_reason, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'refreshed', ?)",
                910_412L,
                fixture.otherHouseholdId(),
                "0".repeat(64),
                fixture.joinedAt().plusDays(1),
                fixture.joinedAt(),
                fixture.ownerUserId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(household_id, actor_id, actor_membership_id, operation_type, "
                        + "idempotency_key, request_fingerprint, result_schema_version, "
                        + "result_payload, created_at, expires_at) "
                        + "VALUES (?, ?, ?, 'member_leave', ?, ?, 1, ?, ?, ?)",
                fixture.householdId(),
                fixture.ownerUserId(),
                fixture.ownerMembershipId(),
                "99999999-9999-4999-8999-999999999999",
                "9".repeat(64),
                "{\"actorHasHousehold\":false}",
                fixture.joinedAt(),
                fixture.joinedAt().plusDays(14)));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "UPDATE dinner_household_members "
                        + "SET status = 'LEFT', end_reason = 'SELF_LEFT' WHERE id = ?",
                fixture.memberMembershipId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, consumed_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                910_411L,
                fixture.otherHouseholdId(),
                "f".repeat(64),
                fixture.joinedAt().plusDays(1),
                fixture.joinedAt(),
                fixture.ownerUserId()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(household_id, actor_id, actor_membership_id, target_member_id, "
                        + "operation_type, idempotency_key, request_fingerprint, "
                        + "result_schema_version, result_payload, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, 'MEMBER_LEAVE', ?, ?, 1, ?, ?, ?)",
                fixture.householdId(),
                fixture.ownerUserId(),
                fixture.ownerMembershipId(),
                fixture.memberMembershipId(),
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "1".repeat(64),
                "{\"actorHasHousehold\":false}",
                fixture.joinedAt(),
                fixture.joinedAt().plusDays(14)));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(household_id, actor_id, actor_membership_id, operation_type, "
                        + "idempotency_key, request_fingerprint, result_schema_version, "
                        + "result_payload, created_at, expires_at) "
                        + "VALUES (?, ?, ?, 'MEMBER_LEAVE', ?, ?, 1, ?, ?, ?)",
                fixture.householdId(),
                fixture.ownerUserId(),
                fixture.ownerMembershipId(),
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "2".repeat(64),
                "{\"actorHasHousehold\":false}",
                fixture.joinedAt(),
                fixture.joinedAt()));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(household_id, actor_id, actor_membership_id, operation_type, "
                        + "idempotency_key, request_fingerprint, result_schema_version, "
                        + "result_payload, created_at, expires_at) "
                        + "VALUES (?, ?, ?, 'MEMBER_LEAVE', ?, ?, 1, ?, ?, ?)",
                fixture.householdId(),
                fixture.ownerUserId(),
                fixture.ownerMembershipId(),
                "ffffffff-ffff-4fff-8fff-ffffffffffff",
                "5".repeat(64),
                "{\"unexpected\":false}",
                fixture.joinedAt(),
                fixture.joinedAt().plusDays(14)));
    }

    private void assertOperationForeignKeyBoundary(
            JdbcTemplate jdbcTemplate,
            String catalog,
            FreshFixture fixture
    ) {
        List<String> foreignKeyColumns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = ? "
                        + "AND TABLE_NAME = 'dinner_household_operations' "
                        + "AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY COLUMN_NAME",
                String.class,
                catalog);
        assertThat(foreignKeyColumns).containsExactly("actor_id");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(CONSTRAINT_NAME, '|', REFERENCED_TABLE_NAME, '|', "
                                + "REFERENCED_COLUMN_NAME) "
                                + "FROM information_schema.KEY_COLUMN_USAGE "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_household_operations' "
                                + "AND COLUMN_NAME = 'actor_id' "
                                + "AND REFERENCED_TABLE_NAME IS NOT NULL",
                        String.class,
                        catalog))
                .isEqualTo("fk_dinner_household_operations_actor|users|id");

        jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(household_id, actor_id, actor_membership_id, target_member_id, "
                        + "operation_type, idempotency_key, request_fingerprint, "
                        + "result_schema_version, result_payload, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, 'OWNER_REMOVE', ?, ?, 1, ?, ?, ?)",
                999_991L,
                fixture.ownerUserId(),
                999_992L,
                999_993L,
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                "3".repeat(64),
                "{\"actorHasHousehold\":true}",
                fixture.joinedAt(),
                fixture.joinedAt().plusDays(14));
        assertIntegrityViolation(() -> jdbcTemplate.update(
                "INSERT INTO dinner_household_operations "
                        + "(household_id, actor_id, actor_membership_id, operation_type, "
                        + "idempotency_key, request_fingerprint, result_schema_version, "
                        + "result_payload, created_at, expires_at) "
                        + "VALUES (?, ?, ?, 'MEMBER_LEAVE', ?, ?, 1, ?, ?, ?)",
                fixture.householdId(),
                999_994L,
                fixture.ownerMembershipId(),
                "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                "4".repeat(64),
                "{\"actorHasHousehold\":false}",
                fixture.joinedAt(),
                fixture.joinedAt().plusDays(14)));
    }

    private LegacyFixture insertV7Fixture(JdbcTemplate jdbcTemplate) {
        long ownerUserId = 920_001L;
        long memberUserId = 920_002L;
        long householdId = 920_101L;
        long ownerMembershipId = 920_201L;
        long memberMembershipId = 920_202L;
        long futureInviteId = 920_301L;
        long expiredInviteId = 920_302L;
        long fallbackCreatedByUserId = 920_003L;
        long fallbackOwnerUserId = 920_004L;
        long fallbackMemberUserId = 920_005L;
        long singleUserId = 920_006L;
        long fallbackHouseholdId = 920_102L;
        long singleHouseholdId = 920_103L;
        long fallbackOwnerMembershipId = 920_203L;
        long fallbackMemberMembershipId = 920_204L;
        long singleMembershipId = 920_205L;
        long retainedInviteId = 920_303L;
        long supersededFutureInviteId = 920_304L;
        long supersededExpiredInviteId = 920_305L;

        insertUser(jdbcTemplate, ownerUserId, "v8-legacy-owner");
        insertUser(jdbcTemplate, memberUserId, "v8-legacy-member");
        insertUser(jdbcTemplate, fallbackCreatedByUserId, "v8-legacy-fallback-creator");
        insertUser(jdbcTemplate, fallbackOwnerUserId, "v8-legacy-fallback-owner");
        insertUser(jdbcTemplate, fallbackMemberUserId, "v8-legacy-fallback-member");
        insertUser(jdbcTemplate, singleUserId, "v8-legacy-single");
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) VALUES (?, ?, ?)",
                householdId,
                "V7 legacy household",
                ownerUserId);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
                ownerMembershipId,
                householdId,
                ownerUserId,
                LocalDateTime.of(2026, 7, 20, 8, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
                memberMembershipId,
                householdId,
                memberUserId,
                LocalDateTime.of(2026, 7, 20, 9, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) VALUES (?, ?, ?)",
                fallbackHouseholdId,
                "V7 fallback-owner household",
                fallbackCreatedByUserId);
        LocalDateTime fallbackJoinedAt = LocalDateTime.of(2026, 7, 20, 11, 0);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
                fallbackOwnerMembershipId,
                fallbackHouseholdId,
                fallbackOwnerUserId,
                fallbackJoinedAt);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
                fallbackMemberMembershipId,
                fallbackHouseholdId,
                fallbackMemberUserId,
                fallbackJoinedAt);
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) VALUES (?, ?, ?)",
                singleHouseholdId,
                "V7 single-member household",
                singleUserId);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
                singleMembershipId,
                singleHouseholdId,
                singleUserId,
                LocalDateTime.of(2026, 7, 20, 12, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                futureInviteId,
                householdId,
                "5".repeat(64),
                LocalDateTime.of(2099, 1, 1, 0, 0),
                ownerUserId,
                LocalDateTime.of(2026, 7, 20, 10, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                expiredInviteId,
                householdId,
                "6".repeat(64),
                LocalDateTime.of(2020, 1, 1, 0, 0),
                ownerUserId,
                LocalDateTime.of(2019, 12, 31, 0, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                retainedInviteId,
                singleHouseholdId,
                "7".repeat(64),
                LocalDateTime.of(2099, 1, 1, 0, 0),
                singleUserId,
                LocalDateTime.of(2026, 7, 20, 11, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                supersededFutureInviteId,
                singleHouseholdId,
                "8".repeat(64),
                LocalDateTime.of(2099, 1, 1, 0, 0),
                singleUserId,
                LocalDateTime.of(2026, 7, 20, 10, 0));
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(id, household_id, code_hash, expires_at, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                supersededExpiredInviteId,
                singleHouseholdId,
                "9".repeat(64),
                LocalDateTime.of(2020, 1, 1, 0, 0),
                singleUserId,
                LocalDateTime.of(2026, 7, 20, 12, 0));
        return new LegacyFixture(
                householdId,
                ownerUserId,
                memberUserId,
                ownerMembershipId,
                memberMembershipId,
                futureInviteId,
                expiredInviteId,
                fallbackHouseholdId,
                fallbackOwnerUserId,
                fallbackMemberUserId,
                fallbackOwnerMembershipId,
                fallbackMemberMembershipId,
                singleHouseholdId,
                singleUserId,
                singleMembershipId,
                retainedInviteId,
                supersededFutureInviteId,
                supersededExpiredInviteId);
    }

    private void assertLegacyBackfill(JdbcTemplate jdbcTemplate, LegacyFixture fixture) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(role, '|', status, '|', seat_no, '|', version, '|', "
                                + "active_user_id, '|', active_owner_household_id, '|', "
                                + "active_seat_no) FROM dinner_household_members WHERE id = ?",
                        String.class,
                        fixture.ownerMembershipId()))
                .isEqualTo("OWNER|ACTIVE|1|1|"
                        + fixture.ownerUserId()
                        + "|"
                        + fixture.householdId()
                        + "|1");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(role, '|', status, '|', seat_no, '|', version, '|', "
                                + "active_user_id, '|', active_seat_no) "
                                + "FROM dinner_household_members WHERE id = ?",
                        String.class,
                        fixture.memberMembershipId()))
                .isEqualTo("MEMBER|ACTIVE|2|1|" + fixture.memberUserId() + "|2");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT history_visible_from FROM dinner_household_members WHERE id = ?",
                        LocalDateTime.class,
                        fixture.ownerMembershipId()))
                .isEqualTo(HISTORY_VISIBLE_FROM);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT history_visible_from FROM dinner_household_members WHERE id = ?",
                        LocalDateTime.class,
                        fixture.memberMembershipId()))
                .isEqualTo(HISTORY_VISIBLE_FROM);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_invite_codes "
                                + "WHERE id IN (?, ?) "
                                + "AND revoked_at IS NOT NULL "
                                + "AND revocation_reason IS NOT NULL "
                                + "AND consumed_at IS NULL "
                                + "AND consumed_by IS NULL "
                                + "AND open_household_id IS NULL",
                        Integer.class,
                        fixture.futureInviteId(),
                        fixture.expiredInviteId()))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT invite_revision FROM dinner_households WHERE id = ?",
                        Long.class,
                        fixture.householdId()))
                .isZero();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(role, '|', seat_no, '|', active_user_id, '|', "
                                + "active_owner_household_id) "
                                + "FROM dinner_household_members WHERE id = ?",
                        String.class,
                        fixture.fallbackOwnerMembershipId()))
                .isEqualTo("OWNER|1|"
                        + fixture.fallbackOwnerUserId()
                        + "|"
                        + fixture.fallbackHouseholdId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(role, '|', seat_no, '|', active_user_id) "
                                + "FROM dinner_household_members WHERE id = ?",
                        String.class,
                        fixture.fallbackMemberMembershipId()))
                .isEqualTo("MEMBER|2|" + fixture.fallbackMemberUserId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT history_visible_from FROM dinner_household_members WHERE id = ?",
                        LocalDateTime.class,
                        fixture.fallbackOwnerMembershipId()))
                .isEqualTo(HISTORY_VISIBLE_FROM);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CONCAT(role, '|', seat_no, '|', active_user_id, '|', "
                                + "active_owner_household_id) "
                                + "FROM dinner_household_members WHERE id = ?",
                        String.class,
                        fixture.singleMembershipId()))
                .isEqualTo("OWNER|1|"
                        + fixture.singleUserId()
                        + "|"
                        + fixture.singleHouseholdId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_invite_codes WHERE id = ? "
                                + "AND revoked_at IS NULL AND revocation_reason IS NULL "
                                + "AND consumed_at IS NULL AND consumed_by IS NULL "
                                + "AND open_household_id = ?",
                        Integer.class,
                        fixture.retainedInviteId(),
                        fixture.singleHouseholdId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_invite_codes WHERE id IN (?, ?) "
                                + "AND revoked_at IS NOT NULL "
                                + "AND revocation_reason = 'MIGRATION_SUPERSEDED' "
                                + "AND open_household_id IS NULL",
                        Integer.class,
                        fixture.supersededFutureInviteId(),
                        fixture.supersededExpiredInviteId()))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT invite_revision FROM dinner_households WHERE id = ?",
                        Long.class,
                        fixture.singleHouseholdId()))
                .isEqualTo(1L);
    }

    private void insertUser(JdbcTemplate jdbcTemplate, long id, String username) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, display_name, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE')",
                id,
                username,
                username);
    }

    private void insertActiveMember(
            JdbcTemplate jdbcTemplate,
            long id,
            long householdId,
            long userId,
            String role,
            int seatNo,
            LocalDateTime joinedAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, joined_at, role, status, seat_no, "
                        + "history_visible_from) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                id,
                householdId,
                userId,
                joinedAt,
                role,
                seatNo,
                joinedAt);
    }

    private void assertGeneratedColumn(
            JdbcTemplate jdbcTemplate,
            String catalog,
            String table,
            String column
    ) {
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT EXTRA, GENERATION_EXPRESSION FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                        catalog,
                        table,
                        column))
                .satisfies(metadata -> {
                    assertThat(metadata.get("EXTRA").toString()).contains("GENERATED");
                    assertThat(metadata.get("GENERATION_EXPRESSION").toString()).isNotBlank();
                });
    }

    private void assertUniqueIndex(
            JdbcTemplate jdbcTemplate,
            String catalog,
            String table,
            List<String> columns
    ) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ("
                                + "SELECT INDEX_NAME, "
                                + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') "
                                + "AS indexed_columns "
                                + "FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND NON_UNIQUE = 0 "
                                + "GROUP BY INDEX_NAME) indexes_for_table "
                                + "WHERE indexed_columns = ?",
                        Integer.class,
                        catalog,
                        table,
                        String.join(",", columns)))
                .as("%s should have a unique index on %s", table, columns)
                .isEqualTo(1);
    }

    private void assertIntegrityViolation(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class);
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

    private static final class V8SmokeCatalogHarness implements AutoCloseable {

        private static final String FAILURE_MESSAGE =
                "Dinner V8 migration smoke requires guarded ephemeral catalogs";
        private static final int MYSQL_IDENTIFIER_LIMIT = 64;
        private static final int MAX_BASE_CATALOG_LENGTH = 15;
        private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
        private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{32}");

        private final DataSource baseDataSource;
        private final String baseCatalog;
        private final String runId;
        private final String freshCatalog;
        private final String v7Catalog;
        private final Set<String> exactRunCatalogs;
        private final Set<String> createdCatalogs = new LinkedHashSet<>();

        private static V8SmokeCatalogHarness fromEnvironment(
                DataSource baseDataSource,
                String effectiveJdbcUrl
        ) {
            return new V8SmokeCatalogHarness(
                    baseDataSource,
                    effectiveJdbcUrl,
                    System.getenv("OSHEEEP_DB_NAME"),
                    System.getenv("OSHEEEP_DB_TEST_NAME"),
                    System.getenv("OSHEEEP_ALLOW_EPHEMERAL_DATABASES"),
                    UUID.randomUUID().toString().replace("-", ""));
        }

        private V8SmokeCatalogHarness(
                DataSource baseDataSource,
                String effectiveJdbcUrl,
                String environmentSelectedCatalog,
                String environmentTestCatalog,
                String environmentOptIn,
                String runId
        ) {
            requireConfiguration(
                    baseDataSource,
                    effectiveJdbcUrl,
                    environmentSelectedCatalog,
                    environmentTestCatalog,
                    environmentOptIn,
                    runId);
            this.baseDataSource = baseDataSource;
            this.baseCatalog = environmentTestCatalog;
            this.runId = runId;
            this.freshCatalog = generatedName("fresh");
            this.v7Catalog = generatedName("v7");
            this.exactRunCatalogs = Set.of(freshCatalog, v7Catalog);
        }

        private String freshCatalog() {
            return freshCatalog;
        }

        private String v7Catalog() {
            return v7Catalog;
        }

        private synchronized void createCatalog(String catalog) {
            requireCurrentRunCatalog(catalog);
            if (createdCatalogs.contains(catalog)) {
                throw unsafe();
            }
            String quotedCatalog = quoteIdentifier(catalog);
            try (Connection connection = baseDataSource.getConnection()) {
                requireGuardedBaseConnection(connection);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "CREATE DATABASE " + quotedCatalog
                                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                    createdCatalogs.add(catalog);
                }
            } catch (SQLException | RuntimeException exception) {
                throw unsafe(exception);
            }
        }

        private synchronized DataSource dataSourceFor(String catalog) {
            requireTrackedCurrentRunCatalog(catalog);
            return new CatalogSwitchingDataSource(baseDataSource, baseCatalog, catalog);
        }

        private void requireActiveCatalog(DataSource dataSource, String catalog) {
            synchronized (this) {
                requireTrackedCurrentRunCatalog(catalog);
            }
            try (Connection connection = dataSource.getConnection()) {
                if (!Objects.equals(catalog, queryScalar(connection, "SELECT DATABASE()"))) {
                    throw unsafe();
                }
            } catch (SQLException exception) {
                throw unsafe(exception);
            }
        }

        private synchronized void dropCatalog(String catalog) {
            requireTrackedCurrentRunCatalog(catalog);
            String quotedCatalog = quoteIdentifier(catalog);
            try (Connection connection = baseDataSource.getConnection()) {
                requireGuardedBaseConnection(connection);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DROP DATABASE " + quotedCatalog);
                    createdCatalogs.remove(catalog);
                }
            } catch (SQLException | RuntimeException exception) {
                throw unsafe(exception);
            }
        }

        private synchronized void dropAll() {
            RuntimeException failure = null;
            for (String catalog : new ArrayList<>(createdCatalogs)) {
                try {
                    dropCatalog(catalog);
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void close() {
            dropAll();
        }

        private void requireConfiguration(
                DataSource dataSource,
                String jdbcUrl,
                String selectedCatalog,
                String testCatalog,
                String optIn,
                String candidateRunId
        ) {
            if (dataSource == null
                    || !hasText(selectedCatalog)
                    || !Objects.equals(selectedCatalog, testCatalog)
                    || !"true".equals(optIn)
                    || !safeIdentifier(testCatalog)
                    || testCatalog.length() > MAX_BASE_CATALOG_LENGTH
                    || !hasText(candidateRunId)
                    || !RUN_ID.matcher(candidateRunId).matches()) {
                throw unsafe();
            }
            JdbcLocation location = parseJdbcLocation(jdbcUrl);
            if (location == null
                    || !allowedLoopbackHost(location.host())
                    || !Objects.equals(testCatalog, location.catalog())) {
                throw unsafe();
            }
            for (String suffix : List.of("fresh", "v7")) {
                String generated = testCatalog + "_ephemeral_" + candidateRunId + "_" + suffix;
                if (!safeIdentifier(generated)
                        || generated.length() > MYSQL_IDENTIFIER_LIMIT) {
                    throw unsafe();
                }
            }
        }

        private JdbcLocation parseJdbcLocation(String jdbcUrl) {
            if (!hasText(jdbcUrl) || !jdbcUrl.startsWith("jdbc:mysql://")) {
                return null;
            }
            try {
                URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
                if (!"mysql".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    return null;
                }
                String path = uri.getPath();
                if (!hasText(path)
                        || !path.startsWith("/")
                        || path.length() == 1
                        || path.substring(1).contains("/")) {
                    return null;
                }
                return new JdbcLocation(normalizeHost(uri.getHost()), path.substring(1));
            } catch (URISyntaxException exception) {
                return null;
            }
        }

        private boolean allowedLoopbackHost(String host) {
            if ("127.0.0.1".equals(host)) {
                return true;
            }
            if (!host.contains(":")) {
                return false;
            }
            try {
                InetAddress address = InetAddress.getByName(host);
                return address instanceof Inet6Address && address.isLoopbackAddress();
            } catch (Exception exception) {
                return false;
            }
        }

        private String normalizeHost(String host) {
            if (host.startsWith("[") && host.endsWith("]")) {
                return host.substring(1, host.length() - 1);
            }
            return host;
        }

        private void requireGuardedBaseConnection(Connection connection) throws SQLException {
            if (!Objects.equals(baseCatalog, queryScalar(connection, "SELECT DATABASE()"))) {
                throw unsafe();
            }
            String version = queryScalar(connection, "SELECT VERSION()");
            if (version == null || !version.startsWith("8.")) {
                throw unsafe();
            }
        }

        private String queryScalar(Connection connection, String sql) throws SQLException {
            try (var statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }

        private String generatedName(String suffix) {
            return baseCatalog + "_ephemeral_" + runId + "_" + suffix;
        }

        private synchronized void requireTrackedCurrentRunCatalog(String catalog) {
            requireCurrentRunCatalog(catalog);
            if (!createdCatalogs.contains(catalog)) {
                throw unsafe();
            }
        }

        private void requireCurrentRunCatalog(String catalog) {
            if (!safeIdentifier(catalog)
                    || catalog.length() > MYSQL_IDENTIFIER_LIMIT
                    || !exactRunCatalogs.contains(catalog)
                    || !catalog.matches(
                            Pattern.quote(baseCatalog)
                                    + "_ephemeral_"
                                    + runId
                                    + "_(fresh|v7)")) {
                throw unsafe();
            }
        }

        private String quoteIdentifier(String identifier) {
            if (!safeIdentifier(identifier)
                    || identifier.length() > MYSQL_IDENTIFIER_LIMIT) {
                throw unsafe();
            }
            return "`" + identifier + "`";
        }

        private boolean safeIdentifier(String value) {
            return hasText(value) && IDENTIFIER.matcher(value).matches();
        }

        private boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }

        private IllegalStateException unsafe() {
            return new IllegalStateException(FAILURE_MESSAGE);
        }

        private IllegalStateException unsafe(Exception cause) {
            return new IllegalStateException(FAILURE_MESSAGE, cause);
        }

        private record JdbcLocation(String host, String catalog) {
        }

        private static final class CatalogSwitchingDataSource implements DataSource {

            private final DataSource delegate;
            private final String baseCatalog;
            private final String targetCatalog;

            private CatalogSwitchingDataSource(
                    DataSource delegate,
                    String baseCatalog,
                    String targetCatalog
            ) {
                this.delegate = delegate;
                this.baseCatalog = baseCatalog;
                this.targetCatalog = targetCatalog;
            }

            @Override
            public Connection getConnection() throws SQLException {
                return switchCatalog(delegate.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return switchCatalog(delegate.getConnection(username, password));
            }

            private Connection switchCatalog(Connection connection) throws SQLException {
                try {
                    connection.setCatalog(targetCatalog);
                    return restoringConnection(connection, baseCatalog);
                } catch (SQLException | RuntimeException failure) {
                    try {
                        connection.close();
                    } catch (SQLException | RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }

            @Override
            public PrintWriter getLogWriter() throws SQLException {
                return delegate.getLogWriter();
            }

            @Override
            public void setLogWriter(PrintWriter out) throws SQLException {
                delegate.setLogWriter(out);
            }

            @Override
            public void setLoginTimeout(int seconds) throws SQLException {
                delegate.setLoginTimeout(seconds);
            }

            @Override
            public int getLoginTimeout() throws SQLException {
                return delegate.getLoginTimeout();
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                return delegate.getParentLogger();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                return delegate.unwrap(iface);
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) throws SQLException {
                return delegate.isWrapperFor(iface);
            }
        }

        private static Connection restoringConnection(
                Connection delegate,
                String baseCatalog
        ) {
            return (Connection) Proxy.newProxyInstance(
                    V8SmokeCatalogHarness.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    new RestoringConnectionHandler(delegate, baseCatalog));
        }

        private static final class RestoringConnectionHandler
                implements java.lang.reflect.InvocationHandler {

            private final Connection delegate;
            private final String baseCatalog;
            private boolean closed;

            private RestoringConnectionHandler(Connection delegate, String baseCatalog) {
                this.delegate = delegate;
                this.baseCatalog = baseCatalog;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                    close();
                    return null;
                }
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }

            private void close() throws Throwable {
                if (closed) {
                    return;
                }
                closed = true;
                Throwable failure = null;
                try {
                    delegate.setCatalog(baseCatalog);
                } catch (SQLException | RuntimeException exception) {
                    failure = exception;
                } finally {
                    try {
                        delegate.close();
                    } catch (SQLException | RuntimeException closeFailure) {
                        if (failure == null) {
                            failure = closeFailure;
                        } else {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }

    private record FreshFixture(
            long householdId,
            long otherHouseholdId,
            long ownerUserId,
            long memberUserId,
            long formerUserId,
            long ownerMembershipId,
            long memberMembershipId,
            long formerMembershipId,
            long operationId,
            long inviteId,
            LocalDateTime joinedAt
    ) {
    }

    private record LegacyFixture(
            long householdId,
            long ownerUserId,
            long memberUserId,
            long ownerMembershipId,
            long memberMembershipId,
            long futureInviteId,
            long expiredInviteId,
            long fallbackHouseholdId,
            long fallbackOwnerUserId,
            long fallbackMemberUserId,
            long fallbackOwnerMembershipId,
            long fallbackMemberMembershipId,
            long singleHouseholdId,
            long singleUserId,
            long singleMembershipId,
            long retainedInviteId,
            long supersededFutureInviteId,
            long supersededExpiredInviteId
    ) {
    }
}

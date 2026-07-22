package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DinnerHouseholdManagementPersistenceContractTest {

    private static final Path MIGRATION_DIRECTORY =
            Path.of("src/main/resources/db/migration");

    private static final Map<String, String> IMMUTABLE_MIGRATION_SHA256 = immutableMigrations();

    @Test
    void v1ThroughV7RemainByteForByteImmutable() throws Exception {
        for (Map.Entry<String, String> baseline : IMMUTABLE_MIGRATION_SHA256.entrySet()) {
            Path migration = MIGRATION_DIRECTORY.resolve(baseline.getKey());

            assertThat(migration)
                    .as("the already-applied migration %s must still exist", baseline.getKey())
                    .exists();
            assertThat(sha256(Files.readAllBytes(migration)))
                    .as("the already-applied migration %s must never be edited", baseline.getKey())
                    .isEqualTo(baseline.getValue());
        }
    }

    @Test
    void v8AddsVersionedHouseholdsAndLifecycleMemberships() throws Exception {
        String sql = normalizedV8();

        assertThat(sql)
                .contains("alter table dinner_households")
                .contains("add column version bigint not null default 1")
                .contains("add column invite_revision bigint not null default 0")
                .contains("add column admin_changed_at datetime(3) null")
                .contains("version >= 1")
                .contains("invite_revision >= 0");

        assertThat(sql)
                .contains("drop index uk_dinner_household_members_user_id")
                .contains("drop index uk_dinner_household_members_household_user")
                .contains("add column role varchar(16) null")
                .contains("add column status varchar(16) not null default 'active'")
                .contains("add column seat_no tinyint null")
                .contains("add column history_visible_from datetime(3) null")
                .contains("add column version bigint not null default 1")
                .contains("add column ended_at datetime(3) null")
                .contains("add column ended_by bigint null")
                .contains("add column end_reason varchar(24) null")
                .contains("add column updated_at datetime(3) not null default current_timestamp(3)"
                        + " on update current_timestamp(3)")
                .contains("active_user_id")
                .contains("active_owner_household_id")
                .contains("active_seat_no")
                .contains("generated always as")
                .contains("stored");
        assertThat(sql)
                .as("nullable staging columns must become mandatory after deterministic backfill")
                .contains("modify role varchar(16) not null")
                .contains("modify seat_no tinyint not null")
                .contains("modify history_visible_from datetime(3) not null");

        assertThat(sql)
                .containsPattern("unique (?:key|index) [a-z0-9_]+ \\(active_user_id\\)")
                .containsPattern("unique (?:key|index) [a-z0-9_]+ \\(active_owner_household_id\\)")
                .containsPattern(
                        "unique (?:key|index) [a-z0-9_]+ \\(household_id, ?active_seat_no\\)")
                .containsPattern(
                        "(?:key|index) [a-z0-9_]+ \\(household_id, ?status, ?role, ?id\\)")
                .containsPattern("(?:key|index) [a-z0-9_]+ \\(user_id, ?joined_at, ?id\\)");

        assertThat(sql)
                .contains("cast(role as binary) in ('owner', 'member')")
                .contains("cast(status as binary) in ('active', 'left', 'removed')")
                .contains("seat_no in (1, 2)")
                .contains("cast(end_reason as binary) in ('self_left', 'owner_removed')")
                .contains("status = 'active' and ended_at is null and ended_by is null"
                        + " and end_reason is null")
                .contains("status <> 'active' and ended_at is not null and ended_by is not null"
                        + " and end_reason is not null");
    }

    @Test
    void v8BackfillsOwnerSeatAndLegacyVisibilityDeterministicallyAndRejectsBadData()
            throws Exception {
        String sql = normalizedV8();

        assertThat(sql)
                .contains("1970-01-01 00:00:00.000")
                .contains("created_by")
                .contains("joined_at")
                .contains("row_number() over")
                .contains("partition by")
                .contains("order by")
                .contains("signal sqlstate '45000'");

        assertThat(sql)
                .as("legacy household validation must inspect zero-member and over-capacity rows")
                .contains("left join dinner_household_members")
                .containsPattern("having count\\([^)]*\\) = 0")
                .containsPattern("count\\([^)]*\\) > 2");

        int createdByOrder = sql.indexOf("created_by");
        int joinedAtOrder = sql.indexOf("joined_at", createdByOrder);
        int idOrder = sql.indexOf("id", joinedAtOrder);
        assertThat(createdByOrder).isNotNegative();
        assertThat(joinedAtOrder).isGreaterThan(createdByOrder);
        assertThat(idOrder).isGreaterThan(joinedAtOrder);
    }

    @Test
    void v8AddsConsumedRevokedAndUniquelyOpenInvitationState() throws Exception {
        String sql = normalizedV8();

        assertThat(sql)
                .contains("alter table dinner_invite_codes")
                .contains("add column consumed_at datetime(3) null")
                .contains("add column consumed_by bigint null")
                .contains("add column revocation_reason varchar(32) null")
                .contains("open_household_id")
                .contains("generated always as")
                .containsPattern("unique (?:key|index) [a-z0-9_]+ \\(open_household_id\\)")
                .contains("legacy_revoked")
                .contains("migration_superseded")
                .contains("refreshed")
                .contains("member_revoked")
                .contains("membership_changed")
                .contains("cast(revocation_reason as binary) in");

        assertThat(sql)
                .contains("consumed_at is null and consumed_by is null")
                .contains("consumed_at is not null and consumed_by is not null")
                .contains("revoked_at is null and revocation_reason is null")
                .contains("revoked_at is not null and revocation_reason is not null")
                .containsPattern(
                        "(?:consumed_at is null and revoked_at is null|"
                                + "not \\(consumed_at is not null and revoked_at is not null\\))");

        assertThat(sql)
                .as("legacy invitation cleanup must rank by eligibility, creation time and id")
                .contains("row_number() over")
                .contains("expires_at")
                .contains("created_at desc")
                .contains("id desc")
                .contains("migration_superseded");
    }

    @Test
    void v8CreatesMinimalOperationLedgerWithOnlyTheActorForeignKey() throws Exception {
        String sql = normalizedV8();
        String operationTable = createTableSection(sql, "dinner_household_operations");

        assertThat(operationTable)
                .contains("id bigint not null auto_increment")
                .contains("primary key (id)")
                .contains("household_id bigint not null")
                .contains("actor_id bigint not null")
                .contains("actor_membership_id bigint not null")
                .contains("target_member_id bigint null")
                .contains("operation_type varchar(32) not null")
                .contains("idempotency_key char(36) character set ascii collate ascii_bin not null")
                .contains("request_fingerprint char(64) character set ascii collate ascii_bin not null")
                .contains("result_schema_version smallint unsigned not null default 1")
                .contains("result_household_version bigint null")
                .contains("result_payload json not null")
                .contains("created_at datetime(3) not null")
                .contains("expires_at datetime(3) not null");

        assertThat(operationTable)
                .contains("foreign key (actor_id) references users (id)")
                .doesNotContain("foreign key (household_id)")
                .doesNotContain("foreign key (actor_membership_id)")
                .doesNotContain("foreign key (target_member_id)");
        assertThat(occurrences(operationTable, "foreign key (")).isEqualTo(1);

        assertThat(operationTable)
                .contains("member_leave")
                .contains("owner_remove")
                .contains("ownership_transfer")
                .contains("household_dissolution")
                .contains("cast(operation_type as binary) in")
                .contains("result_schema_version = 1")
                .contains("result_household_version is null")
                .contains("result_household_version >= 1")
                .contains("expires_at > created_at")
                .contains("json_contains_path(result_payload, 'one', '$.actorhashousehold') = 1")
                .contains("actorhashousehold");
        assertThat(operationTable)
                .as("only owner remove and ownership transfer accept a target")
                .contains("operation_type in ('owner_remove', 'ownership_transfer')"
                        + " and target_member_id is not null")
                .contains("operation_type in ('member_leave', 'household_dissolution')"
                        + " and target_member_id is null");

        assertThat(operationTable)
                .contains("unique key uk_dinner_household_operations_actor_key"
                        + " (actor_id, idempotency_key)")
                .contains("key idx_dinner_household_operations_expiry (expires_at, id)");
    }

    @Test
    void entitiesExposeEveryV8PropertyAndExactColumnMapping() throws Exception {
        Class<?> household = Class.forName(
                "com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity");
        assertTable(household, "dinner_households");
        assertMappedField(household, "version", "version", Long.class);
        assertMappedField(household, "inviteRevision", "invite_revision", Long.class);
        assertMappedField(household, "adminChangedAt", "admin_changed_at", LocalDateTime.class);

        Class<?> member = Class.forName(
                "com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity");
        assertTable(member, "dinner_household_members");
        assertMappedField(member, "role", "role", String.class);
        assertMappedField(member, "status", "status", String.class);
        assertMappedField(member, "seatNo", "seat_no", Integer.class);
        assertMappedField(
                member, "historyVisibleFrom", "history_visible_from", LocalDateTime.class);
        assertMappedField(member, "version", "version", Long.class);
        assertMappedField(member, "endedAt", "ended_at", LocalDateTime.class);
        assertMappedField(member, "endedBy", "ended_by", Long.class);
        assertMappedField(member, "endReason", "end_reason", String.class);
        assertMappedField(member, "updatedAt", "updated_at", LocalDateTime.class);
        assertNeverWriteField(member, "activeUserId", "active_user_id", Long.class);
        assertNeverWriteField(
                member, "activeOwnerHouseholdId", "active_owner_household_id", Long.class);
        assertNeverWriteField(member, "activeSeatNo", "active_seat_no", Integer.class);

        Class<?> invite = Class.forName(
                "com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity");
        assertTable(invite, "dinner_invite_codes");
        assertMappedField(invite, "consumedAt", "consumed_at", LocalDateTime.class);
        assertMappedField(invite, "consumedBy", "consumed_by", Long.class);
        assertMappedField(invite, "revocationReason", "revocation_reason", String.class);
        assertNeverWriteField(invite, "openHouseholdId", "open_household_id", Long.class);

        Class<?> operation = Class.forName(
                "com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity");
        assertTable(operation, "dinner_household_operations");
        assertMappedField(operation, "householdId", "household_id", Long.class);
        assertMappedField(operation, "actorId", "actor_id", Long.class);
        assertMappedField(operation, "actorMembershipId", "actor_membership_id", Long.class);
        assertMappedField(operation, "targetMemberId", "target_member_id", Long.class);
        assertMappedField(operation, "operationType", "operation_type", String.class);
        assertMappedField(operation, "idempotencyKey", "idempotency_key", String.class);
        assertMappedField(operation, "requestFingerprint", "request_fingerprint", String.class);
        assertMappedField(operation, "resultSchemaVersion", "result_schema_version", Integer.class);
        assertMappedField(operation, "resultHouseholdVersion", "result_household_version", Long.class);
        assertMappedField(operation, "resultPayload", "result_payload", String.class);
        assertMappedField(operation, "createdAt", "created_at", LocalDateTime.class);
        assertMappedField(operation, "expiresAt", "expires_at", LocalDateTime.class);
    }

    @Test
    void memberMapperExposesActiveAndHistoryQueriesWithStableOrdering() throws Exception {
        Class<?> mapper = Class.forName(
                "com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper");

        Method activeByUser = mapper.getMethod("selectActiveByUserId", Long.class);
        assertParamNames(activeByUser, "userId");
        assertThat(selectSql(activeByUser))
                .contains("where user_id = #{userid} and status = 'active'")
                .contains("order by id desc")
                .contains("limit 1");

        Method activeByUserForUpdate = mapper.getMethod("selectByUserIdForUpdate", Long.class);
        assertParamNames(activeByUserForUpdate, "userId");
        assertThat(selectSql(activeByUserForUpdate))
                .contains("where user_id = #{userid} and status = 'active'")
                .contains("order by id desc")
                .contains("limit 1")
                .endsWith("for update");

        Method activeForUpdate =
                mapper.getMethod("selectActiveByHouseholdIdForUpdate", Long.class);
        assertThat(activeForUpdate.getReturnType()).isEqualTo(List.class);
        assertParamNames(activeForUpdate, "householdId");
        assertThat(selectSql(activeForUpdate))
                .contains("where household_id = #{householdid} and status = 'active'")
                .contains("order by id")
                .endsWith("for update");

        Method history = mapper.getMethod(
                "selectHistoryByHouseholdAndUserIds", Long.class, List.class);
        assertThat(history.getReturnType()).isEqualTo(List.class);
        assertParamNames(history, "householdId", "userIds");
        assertThat(selectSql(history))
                .contains("where household_id = #{householdid}")
                .contains("user_id in")
                .containsPattern("collection=(?:'|\")userids(?:'|\")")
                .contains("<otherwise>and 1 = 0</otherwise>")
                .contains("order by user_id, joined_at, id");
    }

    @Test
    void inviteAndOperationMappersExposeDeterministicSingleRowQueries() throws Exception {
        Class<?> inviteMapper = Class.forName(
                "com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper");
        Method byCodeHash = inviteMapper.getMethod("selectByCodeHash", String.class);
        assertParamNames(byCodeHash, "codeHash");
        assertThat(selectSql(byCodeHash))
                .contains("where code_hash = #{codehash}")
                .contains("consumed_at is null")
                .contains("revoked_at is null")
                .doesNotContain("expires_at >")
                .contains("order by id desc")
                .contains("limit 1");

        Method activeInvite = inviteMapper.getMethod(
                "selectActiveByHouseholdIdForUpdate", Long.class, LocalDateTime.class);
        assertParamNames(activeInvite, "householdId", "now");
        assertThat(selectSql(activeInvite))
                .contains("where household_id = #{householdid}")
                .contains("consumed_at is null")
                .contains("revoked_at is null")
                .contains("expires_at > #{now}")
                .contains("order by created_at desc, id desc")
                .contains("limit 1")
                .endsWith("for update");

        Method openInvite = inviteMapper.getMethod("selectOpenByHouseholdIdForUpdate", Long.class);
        assertParamNames(openInvite, "householdId");
        assertThat(selectSql(openInvite))
                .contains("where household_id = #{householdid}")
                .contains("consumed_at is null")
                .contains("revoked_at is null")
                .contains("order by created_at desc, id desc")
                .contains("limit 1")
                .endsWith("for update");

        Method inviteById = inviteMapper.getMethod(
                "selectByIdAndHouseholdIdForUpdate", Long.class, Long.class);
        assertParamNames(inviteById, "inviteId", "householdId");
        assertThat(selectSql(inviteById))
                .contains("where id = #{inviteid} and household_id = #{householdid}")
                .contains("consumed_at is null")
                .contains("revoked_at is null")
                .contains("order by id asc")
                .contains("limit 1")
                .endsWith("for update");

        Class<?> operationMapper = Class.forName(
                "com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper");
        Method byActorAndKey = operationMapper.getMethod(
                "selectByActorAndIdempotencyKey", Long.class, String.class);
        assertParamNames(byActorAndKey, "actorId", "idempotencyKey");
        assertThat(selectSql(byActorAndKey))
                .contains("where actor_id = #{actorid} and idempotency_key = #{idempotencykey}")
                .contains("order by id desc")
                .contains("limit 1");
    }

    private static Map<String, String> immutableMigrations() {
        Map<String, String> migrations = new LinkedHashMap<>();
        migrations.put("V1__init_schema.sql", "e39ac21d229be238f348348d4d2d092b42b7974d3fb4f4b5a8a33b573d04df61");
        migrations.put("V2__add_wechat_identity.sql", "8e73a208a91a97bca27e6d89aef17b20af25a3f3a2f9f9adbe6960ec8a8c4631");
        migrations.put("V3__add_dinner_households.sql", "c191c687fd1d3c6e134e5423cf1269e2884c233a478131ec55320b774958b93a");
        migrations.put("V4__add_dinner_menus_and_records.sql", "6fc9d68156a00f11ef7852f74b4f8ac565237c8183f4c71bc3e5713718b53c8d");
        migrations.put("V5__add_recipe_ingredients_and_household_inventory.sql", "73f18c7bedffaec0f03ae5dfa987c78de504f399e1d3f53d63a24c6302df3009");
        migrations.put("V6__add_household_custom_recipes.sql", "3ed0a3f39e31ebcd3f27593e0cc5201792ac9ee98d3f02d2336c188ee28cf94c");
        migrations.put("V7__connect_household_recipes_to_menus.sql", "7ce7255a0086e44234d4de0138e00126999adf225115fa47c10c2af872fd997d");
        return Map.copyOf(migrations);
    }

    private static String normalizedV8() throws Exception {
        Path migration = MIGRATION_DIRECTORY.resolve("V8__add_household_management.sql");
        assertThat(migration).as("V8 household management migration").exists();
        return normalize(Files.readString(migration));
    }

    private static String createTableSection(String sql, String tableName) {
        String marker = "create table " + tableName;
        int start = sql.indexOf(marker);
        assertThat(start).as("CREATE TABLE %s", tableName).isNotNegative();
        int end = sql.indexOf(';', start);
        assertThat(end).as("terminating semicolon for CREATE TABLE %s", tableName)
                .isGreaterThan(start);
        return sql.substring(start, end + 1);
    }

    private static String selectSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("@Select on %s", method).isNotNull();
        return normalize(String.join(" ", select.value()))
                .replace("<script>", "")
                .replace("</script>", "");
    }

    private static void assertParamNames(Method method, String... expectedNames) {
        assertThat(method.getParameterCount()).isEqualTo(expectedNames.length);
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < expectedNames.length; index++) {
            Param param = parameters[index].getAnnotation(Param.class);
            assertThat(param).as("@Param on parameter %s of %s", index, method).isNotNull();
            assertThat(param.value()).isEqualTo(expectedNames[index]);
        }
    }

    private static void assertTable(Class<?> entity, String tableName) {
        TableName mapping = entity.getAnnotation(TableName.class);
        assertThat(mapping).as("@TableName on %s", entity.getName()).isNotNull();
        assertThat(mapping.value()).isEqualTo(tableName);
    }

    private static void assertMappedField(
            Class<?> entity, String property, String column, Class<?> expectedType) throws Exception {
        Field field = entity.getDeclaredField(property);
        if (expectedType != null) {
            assertThat(field.getType()).as("type of %s.%s", entity.getSimpleName(), property)
                    .isEqualTo(expectedType);
        }
        TableField mapping = field.getAnnotation(TableField.class);
        assertThat(mapping).as("@TableField on %s.%s", entity.getSimpleName(), property).isNotNull();
        assertThat(mapping.value()).isEqualTo(column);
    }

    private static void assertNeverWriteField(
            Class<?> entity, String property, String column, Class<?> expectedType) throws Exception {
        assertMappedField(entity, property, column, expectedType);
        TableField mapping = entity.getDeclaredField(property).getAnnotation(TableField.class);
        assertThat(mapping.insertStrategy()).isEqualTo(FieldStrategy.NEVER);
        assertThat(mapping.updateStrategy()).isEqualTo(FieldStrategy.NEVER);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String normalize(String value) {
        return value.replace('`', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}

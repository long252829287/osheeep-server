package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.dinner.recipe.DinnerCustomRecipeFlywayMigrationStrategy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MySQL acceptance coverage for the transaction guarantees that mocks cannot prove.
 *
 * <p>The safety initializer refuses to start unless the local profile points at the explicit
 * disposable database selected by both {@code OSHEEEP_DB_NAME} and
 * {@code OSHEEEP_DB_TEST_NAME}.</p>
 */
@ActiveProfiles("local")
@SpringBootTest
@ContextConfiguration(
        initializers = DinnerMembershipTerminationTestDatabaseSafetyInitializer.class)
@Import(DinnerMembershipTerminationMySqlIT.FlywaySafetyConfiguration.class)
class DinnerMembershipTerminationMySqlIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DinnerHouseholdOperationService operationService;
    @MockitoSpyBean private DinnerHouseholdOperationMapper operationMapper;
    @MockitoSpyBean private DinnerHouseholdMemberMapper memberMapper;

    private Long ownerUserId;
    private Long memberUserId;
    private Long householdId;
    private Long ownerMembershipId;
    private Long memberMembershipId;
    private Long inviteId;
    private Long menuId;
    private Long draftRecipeId;
    private Long systemSourceRecipeId;
    private Long householdIngredientId;
    private Long inventoryId;
    private String suffix;

    @BeforeEach
    void seedDedicatedV8Database() {
        reset(operationMapper, memberMapper);
        requireDedicatedV8Database();

        suffix = UUID.randomUUID().toString().replace("-", "");
        ownerUserId = insertUser("termination_owner_" + suffix);
        memberUserId = insertUser("termination_member_" + suffix);
        householdId = insertHousehold();
        ownerMembershipId = insertMembership(ownerUserId, "OWNER", 1);
        memberMembershipId = insertMembership(memberUserId, "MEMBER", 2);
        inviteId = insertOpenInvite();
        systemSourceRecipeId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM dinner_recipes WHERE scope = 'SYSTEM'",
                Long.class);
        assertThat(systemSourceRecipeId).as("a retained system source fixture").isNotNull();
        householdIngredientId = insertHouseholdIngredient();
        draftRecipeId = insertMemberDraft();
        insertDraftIngredient();
        inventoryId = insertInventory();
        menuId = insertConfirmedMenu();
        insertMemberSelection();
    }

    @AfterEach
    void deleteOnlySeededRows() {
        reset(operationMapper, memberMapper);
        if (suffix == null) {
            return;
        }
        requireDedicatedCatalogAtRuntime();
        if (householdId != null) {
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_operations WHERE household_id = ?",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_menu_selections WHERE menu_id IN "
                            + "(SELECT id FROM dinner_menus WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update("DELETE FROM dinner_menus WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_recipe_ingredients WHERE recipe_id IN "
                            + "(SELECT id FROM dinner_recipes WHERE household_id = ? "
                            + "OR (household_id IS NULL AND creator_id IN (?, ?)))",
                    householdId,
                    ownerUserId,
                    memberUserId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_inventory WHERE household_id = ?",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_recipes WHERE household_id = ? "
                            + "OR (household_id IS NULL AND creator_id IN (?, ?))",
                    householdId,
                    ownerUserId,
                    memberUserId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_ingredients WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_invite_codes WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_members WHERE household_id = ?", householdId);
            jdbcTemplate.update("DELETE FROM dinner_households WHERE id = ?", householdId);
        }
        if (memberUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberUserId);
        }
        if (ownerUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerUserId);
        }
    }

    @Test
    @Timeout(20)
    void concurrentOuterMissesSerializeOnActorAndSecondRequestReplays() throws Exception {
        String key = UUID.randomUUID().toString();
        CyclicBarrier bothOuterQueriesMissed = new CyclicBarrier(2);
        AtomicInteger guardedOuterQueries = new AtomicInteger();
        Answer<?> realMapperDelegate = Mockito.mockingDetails(operationMapper)
                .getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = realMapperDelegate.answer(invocation);
            int call = guardedOuterQueries.incrementAndGet();
            if (call <= 2) {
                assertThat(result).as("both real outer queries must miss").isNull();
                bothOuterQueriesMissed.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(operationMapper).selectByActorAndIdempotencyKey(memberUserId, key);

        List<HouseholdMutationResponse> responses;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HouseholdMutationResponse> first = executor.submit(
                    () -> operationService.leave(
                            memberUserId, memberMembershipId, 1L, key));
            Future<HouseholdMutationResponse> second = executor.submit(
                    () -> operationService.leave(
                            memberUserId, memberMembershipId, 1L, key));
            responses = List.of(await(first), await(second));
        }

        assertThat(guardedOuterQueries).hasValue(2);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::operationType)
                .containsOnly(DinnerHouseholdOperationService.MEMBER_LEAVE);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::actorHasHousehold)
                .containsOnly(false);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::householdVersion)
                .containsOnly(2L);

        verify(operationMapper, times(2))
                .selectByActorAndIdempotencyKeyForUpdate(memberUserId, key);
        verify(memberMapper, times(1)).selectActiveByUserId(memberUserId);
        assertCommittedTermination(key);
    }

    @Test
    void operationInsertFailureRollsBackEveryEarlierAggregateMutation() {
        AtomicInteger insertAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            insertAttempts.incrementAndGet();
            assertTerminationAggregateState();
            throw new DataIntegrityViolationException("forced operation insert failure");
        }).when(operationMapper).insert(any(DinnerHouseholdOperationEntity.class));

        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> operationService.leave(
                memberUserId, memberMembershipId, 1L, key))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("forced operation insert failure");

        assertThat(insertAttempts).hasValue(1);
        assertOriginalAggregateState(key);
    }

    private void assertCommittedTermination(String key) {
        assertTerminationAggregateState();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                memberUserId,
                key)).isEqualTo(1);
        LocalDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                LocalDateTime.class,
                memberUserId,
                key);
        LocalDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                LocalDateTime.class,
                memberUserId,
                key);
        assertThat(expiresAt).isEqualTo(createdAt.plusDays(14));
    }

    private void assertTerminationAggregateState() {
        Map<String, Object> member = jdbcTemplate.queryForMap(
                "SELECT status, version, ended_by, end_reason, ended_at "
                        + "FROM dinner_household_members WHERE id = ?",
                memberMembershipId);
        assertThat(member.get("status")).isEqualTo("LEFT");
        assertThat(((Number) member.get("version")).longValue()).isEqualTo(2L);
        assertThat(((Number) member.get("ended_by")).longValue()).isEqualTo(memberUserId);
        assertThat(member.get("end_reason")).isEqualTo("SELF_LEFT");
        assertThat(member.get("ended_at")).isNotNull();

        Map<String, Object> household = jdbcTemplate.queryForMap(
                "SELECT version, invite_revision FROM dinner_households WHERE id = ?",
                householdId);
        assertThat(((Number) household.get("version")).longValue()).isEqualTo(2L);
        assertThat(((Number) household.get("invite_revision")).longValue()).isEqualTo(1L);

        Map<String, Object> invite = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revocation_reason FROM dinner_invite_codes WHERE id = ?",
                inviteId);
        assertThat(invite.get("revoked_at")).isNotNull();
        assertThat(invite.get("revocation_reason")).isEqualTo("MEMBERSHIP_CHANGED");

        Map<String, Object> menu = jdbcTemplate.queryForMap(
                "SELECT status, version, confirmed_by, confirmed_at "
                        + "FROM dinner_menus WHERE id = ?",
                menuId);
        assertThat(menu.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) menu.get("version")).longValue()).isEqualTo(5L);
        assertThat(menu.get("confirmed_by")).isNull();
        assertThat(menu.get("confirmed_at")).isNull();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_menu_selections WHERE menu_id = ?",
                menuId)).isZero();

        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT household_id, version, source_recipe_id, revision_of_recipe_id, "
                        + "base_published_version FROM dinner_recipes WHERE id = ?",
                draftRecipeId);
        assertThat(draft.get("household_id")).isNull();
        assertThat(((Number) draft.get("version")).longValue()).isEqualTo(4L);
        assertThat(((Number) draft.get("source_recipe_id")).longValue())
                .isEqualTo(systemSourceRecipeId);
        assertThat(draft.get("revision_of_recipe_id")).isNull();
        assertThat(draft.get("base_published_version")).isNull();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_recipe_ingredients WHERE recipe_id = ?",
                draftRecipeId)).isZero();

        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_inventory WHERE id = ?",
                inventoryId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_ingredients WHERE id = ?",
                householdIngredientId)).isEqualTo(1);
    }

    private void assertOriginalAggregateState(String key) {
        Map<String, Object> member = jdbcTemplate.queryForMap(
                "SELECT status, version, ended_by, end_reason, ended_at "
                        + "FROM dinner_household_members WHERE id = ?",
                memberMembershipId);
        assertThat(member.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) member.get("version")).longValue()).isEqualTo(1L);
        assertThat(member.get("ended_by")).isNull();
        assertThat(member.get("end_reason")).isNull();
        assertThat(member.get("ended_at")).isNull();

        Map<String, Object> household = jdbcTemplate.queryForMap(
                "SELECT version, invite_revision FROM dinner_households WHERE id = ?",
                householdId);
        assertThat(((Number) household.get("version")).longValue()).isEqualTo(1L);
        assertThat(((Number) household.get("invite_revision")).longValue()).isZero();

        Map<String, Object> invite = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revocation_reason FROM dinner_invite_codes WHERE id = ?",
                inviteId);
        assertThat(invite.get("revoked_at")).isNull();
        assertThat(invite.get("revocation_reason")).isNull();

        Map<String, Object> menu = jdbcTemplate.queryForMap(
                "SELECT status, version, confirmed_by, confirmed_at "
                        + "FROM dinner_menus WHERE id = ?",
                menuId);
        assertThat(menu.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) menu.get("version")).longValue()).isEqualTo(4L);
        assertThat(((Number) menu.get("confirmed_by")).longValue()).isEqualTo(ownerUserId);
        assertThat(menu.get("confirmed_at")).isNotNull();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_menu_selections WHERE menu_id = ?",
                menuId)).isEqualTo(1);

        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT household_id, version, source_recipe_id "
                        + "FROM dinner_recipes WHERE id = ?",
                draftRecipeId);
        assertThat(((Number) draft.get("household_id")).longValue()).isEqualTo(householdId);
        assertThat(((Number) draft.get("version")).longValue()).isEqualTo(3L);
        assertThat(((Number) draft.get("source_recipe_id")).longValue())
                .isEqualTo(systemSourceRecipeId);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_recipe_ingredients WHERE recipe_id = ?",
                draftRecipeId)).isEqualTo(1);

        Map<String, Object> inventory = jdbcTemplate.queryForMap(
                "SELECT quantity, version FROM dinner_household_inventory WHERE id = ?",
                inventoryId);
        assertThat(new BigDecimal(inventory.get("quantity").toString()))
                .isEqualByComparingTo("2.000");
        assertThat(((Number) inventory.get("version")).longValue()).isEqualTo(1L);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_ingredients WHERE id = ?",
                householdIngredientId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                memberUserId,
                key)).isZero();
    }

    private void requireDedicatedV8Database() {
        requireDedicatedCatalogAtRuntime();
        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class))
                .as("membership termination acceptance must run on MySQL 8")
                .startsWith("8.");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
                        String.class))
                .as("membership termination acceptance requires Flyway V8")
                .isEqualTo("8");
    }

    private void requireDedicatedCatalogAtRuntime() {
        String expected = System.getenv("OSHEEEP_DB_TEST_NAME");
        assertThat(expected).as("OSHEEEP_DB_TEST_NAME safety gate").isNotBlank();
        assertThat(System.getenv("OSHEEEP_DB_NAME"))
                .as("raw selected database must be the dedicated test database")
                .isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .as("active catalog must be the dedicated test database")
                .isEqualTo(expected);
    }

    private Long insertUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO users (username, status) VALUES (?, 'ACTIVE')", username);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private Long insertHousehold() {
        String name = "termination_household_" + suffix;
        jdbcTemplate.update(
                "INSERT INTO dinner_households (name, created_by) VALUES (?, ?)",
                name,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_households WHERE name = ? AND created_by = ?",
                Long.class,
                name,
                ownerUserId);
    }

    private Long insertMembership(Long userId, String role, int seatNo) {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(household_id, user_id, role, status, seat_no, "
                        + "history_visible_from, version) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(3), 1)",
                householdId,
                userId,
                role,
                seatNo);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_household_members "
                        + "WHERE household_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Long.class,
                householdId,
                userId);
    }

    private Long insertOpenInvite() {
        String hash = suffix + suffix;
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(household_id, code_hash, expires_at, created_by) "
                        + "VALUES (?, ?, UTC_TIMESTAMP(3) + INTERVAL 1 DAY, ?)",
                householdId,
                hash,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_invite_codes WHERE code_hash = ?", Long.class, hash);
    }

    private Long insertHouseholdIngredient() {
        String name = "termination_ingredient_" + suffix.substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO dinner_ingredients "
                        + "(scope, household_id, name, category, default_unit, status) "
                        + "VALUES ('HOUSEHOLD', ?, ?, 'TEST', '个', 'ACTIVE')",
                householdId,
                name);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_ingredients WHERE household_id = ? AND name = ?",
                Long.class,
                householdId,
                name);
    }

    private Long insertMemberDraft() {
        String name = "termination_draft_" + suffix.substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO dinner_recipes "
                        + "(scope, household_id, name, category, flavor, servings, "
                        + "estimated_minutes, creator_id, last_modified_by, source_recipe_id, "
                        + "status, version) "
                        + "VALUES ('HOUSEHOLD', ?, ?, 'TEST', 'TEST', 2, 10, ?, ?, ?, "
                        + "'DRAFT', 3)",
                householdId,
                name,
                memberUserId,
                memberUserId,
                systemSourceRecipeId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_recipes WHERE household_id = ? AND name = ?",
                Long.class,
                householdId,
                name);
    }

    private void insertDraftIngredient() {
        jdbcTemplate.update(
                "INSERT INTO dinner_recipe_ingredients "
                        + "(recipe_id, ingredient_id, quantity, unit, is_required, sort_order) "
                        + "VALUES (?, ?, 1.000, '个', 1, 1)",
                draftRecipeId,
                householdIngredientId);
    }

    private Long insertInventory() {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_inventory "
                        + "(household_id, ingredient_id, quantity, unit, version, updated_by) "
                        + "VALUES (?, ?, 2.000, '个', 1, ?)",
                householdId,
                householdIngredientId,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_household_inventory "
                        + "WHERE household_id = ? AND ingredient_id = ?",
                Long.class,
                householdId,
                householdIngredientId);
    }

    private Long insertConfirmedMenu() {
        jdbcTemplate.update(
                "INSERT INTO dinner_menus "
                        + "(household_id, menu_date, status, version, confirmed_by, confirmed_at) "
                        + "VALUES (?, CURRENT_DATE + INTERVAL 30 DAY, 'CONFIRMED', 4, ?, "
                        + "UTC_TIMESTAMP(3))",
                householdId,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_menus WHERE household_id = ?",
                Long.class,
                householdId);
    }

    private void insertMemberSelection() {
        jdbcTemplate.update(
                "INSERT INTO dinner_menu_selections "
                        + "(menu_id, user_id, recipe_id, recipe_version) VALUES (?, ?, ?, 3)",
                menuId,
                memberUserId,
                draftRecipeId);
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private HouseholdMutationResponse await(Future<HouseholdMutationResponse> future)
            throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlywaySafetyConfiguration {

        @Bean
        FlywayMigrationStrategy membershipTerminationFlywayMigrationStrategy() {
            return new DinnerCustomRecipeFlywayMigrationStrategy(
                    System.getenv("OSHEEEP_DB_TEST_NAME"));
        }
    }
}

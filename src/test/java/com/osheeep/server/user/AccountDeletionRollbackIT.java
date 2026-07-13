package com.osheeep.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.osheeep.server.dinner.household.DinnerAccountCleanupService;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@ActiveProfiles("local")
@SpringBootTest
@ContextConfiguration(initializers = AccountDeletionTestDatabaseSafetyInitializer.class)
class AccountDeletionRollbackIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountDeletionTransaction transaction;

    @MockitoSpyBean
    private DinnerAccountCleanupService dinnerCleanup;

    private Long userId;
    private Long householdId;
    private Long recipeId;
    private Long menuId;
    private Long recordId;

    @BeforeEach
    void setUp() {
        String username = "rollback_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                "INSERT INTO users (username, status) VALUES (?, 'ACTIVE')", username);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        jdbcTemplate.update(
                "INSERT INTO wechat_user_identities (user_id, openid) VALUES (?, ?)",
                userId, "rollback-openid");
        doThrow(new IllegalStateException("forced cleanup failure"))
                .when(dinnerCleanup).removeUser(eq(userId), any(LocalDateTime.class));
    }

    @AfterEach
    void cleanUp() {
        if (householdId != null) {
            jdbcTemplate.update(
                    "DELETE FROM dinner_record_dish_snapshots WHERE record_id IN "
                            + "(SELECT id FROM dinner_cooking_records WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_cooking_records WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_menu_actions WHERE menu_id IN "
                            + "(SELECT id FROM dinner_menus WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_menu_selections WHERE menu_id IN "
                            + "(SELECT id FROM dinner_menus WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update("DELETE FROM dinner_menus WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_invite_codes WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_members WHERE household_id = ?", householdId);
            jdbcTemplate.update("DELETE FROM dinner_recipes WHERE household_id = ?", householdId);
            jdbcTemplate.update("DELETE FROM dinner_households WHERE id = ?", householdId);
        }
        jdbcTemplate.update("DELETE FROM wechat_user_identities WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void cleanupFailureRollsBackIdentityAndUserChanges() {
        assertThatThrownBy(() -> transaction.deleteVerified(userId, "rollback-openid"))
                .isInstanceOf(IllegalStateException.class);

        Integer identities = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wechat_user_identities WHERE user_id = ?",
                Integer.class, userId);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM users WHERE id = ?", String.class, userId);
        assertThat(identities).isEqualTo(1);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void successfulDeletionPersistsAnonymizedUserFields() {
        jdbcTemplate.update(
                "UPDATE users SET email = ?, password_hash = ?, display_name = ?, avatar_url = ? "
                        + "WHERE id = ?",
                "private@example.com", "hash", "Private Name",
                "https://example.com/avatar.jpg", userId);
        doNothing().when(dinnerCleanup).removeUser(eq(userId), any(LocalDateTime.class));

        transaction.deleteVerified(userId, "rollback-openid");

        Integer identities = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wechat_user_identities WHERE user_id = ?",
                Integer.class, userId);
        var user = jdbcTemplate.queryForMap(
                "SELECT username, email, password_hash, display_name, avatar_url, status, "
                        + "deleted_at "
                        + "FROM users WHERE id = ?",
                userId);
        assertThat(identities).isZero();
        assertThat(user.get("username")).isEqualTo("deleted_user_" + userId);
        assertThat(user.get("email")).isNull();
        assertThat(user.get("password_hash")).isNull();
        assertThat(user.get("display_name")).isNull();
        assertThat(user.get("avatar_url")).isNull();
        assertThat(user.get("status")).isEqualTo("DELETED");
        assertThat(user.get("deleted_at")).isNotNull();
    }

    @Test
    void failureAfterRealDinnerCleanupRollsBackEveryMutation() throws Throwable {
        seedDinnerHouseholdHistory();
        jdbcTemplate.update(
                "UPDATE users SET email = ?, password_hash = ?, display_name = ?, avatar_url = ? "
                        + "WHERE id = ?",
                "private@example.com", "original-hash", "Original Name",
                "https://example.com/original.jpg", userId);
        AtomicBoolean cleanupWritesObserved = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(count("dinner_households", "id", householdId)).isZero();
            assertThat(count("dinner_household_members", "household_id", householdId)).isZero();
            assertThat(count("dinner_record_dish_snapshots", "record_id", recordId)).isZero();
            cleanupWritesObserved.set(true);
            throw new IllegalStateException("forced failure after cleanup writes");
        }).when(dinnerCleanup).removeUser(eq(userId), any(LocalDateTime.class));

        assertThatThrownBy(() -> transaction.deleteVerified(userId, "rollback-openid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced failure after cleanup writes");

        assertThat(cleanupWritesObserved).isTrue();
        assertThat(count("wechat_user_identities", "user_id", userId)).isEqualTo(1);
        var user = jdbcTemplate.queryForMap(
                "SELECT username, email, password_hash, display_name, avatar_url, status, "
                        + "deleted_at FROM users WHERE id = ?",
                userId);
        assertThat(user.get("username")).asString().startsWith("rollback_");
        assertThat(user.get("email")).isEqualTo("private@example.com");
        assertThat(user.get("password_hash")).isEqualTo("original-hash");
        assertThat(user.get("display_name")).isEqualTo("Original Name");
        assertThat(user.get("avatar_url")).isEqualTo("https://example.com/original.jpg");
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat(user.get("deleted_at")).isNull();
        assertThat(count("dinner_households", "id", householdId)).isEqualTo(1);
        assertThat(count("dinner_household_members", "household_id", householdId)).isEqualTo(1);
        assertThat(count("dinner_invite_codes", "household_id", householdId)).isEqualTo(1);
        assertThat(count("dinner_recipes", "id", recipeId)).isEqualTo(1);
        assertThat(count("dinner_menus", "id", menuId)).isEqualTo(1);
        assertThat(count("dinner_menu_selections", "menu_id", menuId)).isEqualTo(1);
        assertThat(count("dinner_menu_actions", "menu_id", menuId)).isEqualTo(1);
        assertThat(count("dinner_cooking_records", "id", recordId)).isEqualTo(1);
        assertThat(count("dinner_record_dish_snapshots", "record_id", recordId)).isEqualTo(1);
    }

    private void seedDinnerHouseholdHistory() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String householdName = "rollback-household-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO dinner_households (name, created_by) VALUES (?, ?)",
                householdName, userId);
        householdId = jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_households WHERE name = ? AND created_by = ?",
                Long.class, householdName, userId);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members (household_id, user_id) VALUES (?, ?)",
                householdId, userId);
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(household_id, code_hash, expires_at, created_by) VALUES (?, ?, ?, ?)",
                householdId, suffix + suffix,
                LocalDateTime.parse("2026-07-14T12:00:00"), userId);

        String recipeName = "rollback-recipe-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO dinner_recipes "
                        + "(scope, household_id, name, category, flavor, estimated_minutes, "
                        + "creator_id) VALUES ('HOUSEHOLD', ?, ?, '家常菜', '清淡', 10, ?)",
                householdId, recipeName, userId);
        recipeId = jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_recipes WHERE household_id = ? AND name = ?",
                Long.class, householdId, recipeName);

        jdbcTemplate.update(
                "INSERT INTO dinner_menus (household_id, menu_date, status, version) "
                        + "VALUES (?, '2026-07-13', 'DRAFT', 0)",
                householdId);
        menuId = jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_menus WHERE household_id = ? AND menu_date = '2026-07-13'",
                Long.class, householdId);
        jdbcTemplate.update(
                "INSERT INTO dinner_menu_selections (menu_id, user_id, recipe_id) "
                        + "VALUES (?, ?, ?)",
                menuId, userId, recipeId);
        jdbcTemplate.update(
                "INSERT INTO dinner_menu_actions "
                        + "(menu_id, actor_id, action_type, idempotency_key) "
                        + "VALUES (?, ?, 'SELECT', ?)",
                menuId, userId, UUID.randomUUID().toString());

        jdbcTemplate.update(
                "INSERT INTO dinner_cooking_records "
                        + "(household_id, menu_id, record_date, completed_by, completed_at) "
                        + "VALUES (?, ?, '2026-07-13', ?, ?)",
                householdId, menuId, userId, LocalDateTime.parse("2026-07-13T12:00:00"));
        recordId = jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_cooking_records WHERE menu_id = ?",
                Long.class, menuId);
        jdbcTemplate.update(
                "INSERT INTO dinner_record_dish_snapshots "
                        + "(record_id, recipe_id, name, category, flavor, estimated_minutes, "
                        + "selected_by_user_ids, sort_order) "
                        + "VALUES (?, ?, ?, '家常菜', '清淡', 10, ?, 0)",
                recordId, recipeId, recipeName, "[" + userId + "]");
    }

    private int count(String table, String column, Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id);
    }
}

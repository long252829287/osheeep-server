package com.osheeep.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.osheeep.server.dinner.household.DinnerAccountCleanupService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("local")
@SpringBootTest
class AccountDeletionRollbackIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountDeletionTransaction transaction;

    @MockitoBean
    private DinnerAccountCleanupService dinnerCleanup;

    private Long userId;

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
                "SELECT username, email, password_hash, display_name, avatar_url, status "
                        + "FROM users WHERE id = ?",
                userId);
        assertThat(identities).isZero();
        assertThat(user.get("username")).isEqualTo("deleted_user_" + userId);
        assertThat(user.get("email")).isNull();
        assertThat(user.get("password_hash")).isNull();
        assertThat(user.get("display_name")).isNull();
        assertThat(user.get("avatar_url")).isNull();
        assertThat(user.get("status")).isEqualTo("DELETED");
    }
}

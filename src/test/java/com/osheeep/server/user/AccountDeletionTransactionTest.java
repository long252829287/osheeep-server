package com.osheeep.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerAccountCleanupService;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountDeletionTransactionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime DELETED_AT =
            LocalDateTime.parse("2026-07-13T12:00:00");

    @Mock private UserMapper userMapper;
    @Mock private UserService userService;
    @Mock private WechatUserIdentityMapper identityMapper;
    @Mock private DinnerAccountCleanupService dinnerCleanup;

    private AccountDeletionTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new AccountDeletionTransaction(
                userMapper, userService, identityMapper, dinnerCleanup, CLOCK);
    }

    @Test
    void rejectsDifferentWechatIdentityWithoutChangingData() {
        UserEntity user = activeUser(7L);
        WechatUserIdentityEntity identity = identity(71L, 7L, "openid-7");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
        when(userService.isActive(user)).thenReturn(true);
        when(identityMapper.selectOne(any())).thenReturn(identity);

        assertThatThrownBy(() -> transaction.deleteVerified(7L, "openid-other"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.ACCOUNT_DELETION_IDENTITY_MISMATCH));

        verify(dinnerCleanup, never()).removeUser(anyLong(), any());
        verify(identityMapper, never()).deleteById(anyLong());
        verify(userMapper, never()).anonymizeActiveUser(anyLong(), any(), any());
    }

    @Test
    void deletesIdentityCleansDinnerDataAndAnonymizesUser() {
        UserEntity user = activeUser(7L);
        user.setEmail("private@example.com");
        user.setPasswordHash("hash");
        user.setDisplayName("Private Name");
        user.setAvatarUrl("https://example.com/avatar.jpg");
        WechatUserIdentityEntity identity = identity(71L, 7L, "openid-7");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
        when(userService.isActive(user)).thenReturn(true);
        when(identityMapper.selectOne(any())).thenReturn(identity);
        when(identityMapper.deleteById(71L)).thenReturn(1);
        when(userMapper.anonymizeActiveUser(7L, "deleted_user_7", DELETED_AT)).thenReturn(1);

        transaction.deleteVerified(7L, "openid-7");

        verify(identityMapper).deleteById(71L);
        verify(dinnerCleanup).removeUser(7L, DELETED_AT);
        verify(userMapper).anonymizeActiveUser(7L, "deleted_user_7", DELETED_AT);
        verify(userMapper, never()).updateById(any(UserEntity.class));
    }

    @Test
    void identityDeletionAffectingNoRowsFailsBeforeCleanup() {
        UserEntity user = activeUser(7L);
        WechatUserIdentityEntity identity = identity(71L, 7L, "openid-7");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
        when(userService.isActive(user)).thenReturn(true);
        when(identityMapper.selectOne(any())).thenReturn(identity);
        when(identityMapper.deleteById(71L)).thenReturn(0);

        assertThatThrownBy(() -> transaction.deleteVerified(7L, "openid-7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one WeChat identity row to be deleted");

        verifyNoInteractions(dinnerCleanup);
        verify(userMapper, never()).anonymizeActiveUser(anyLong(), any(), any());
    }

    @Test
    void userAnonymizationAffectingNoRowsFailsAfterCleanup() {
        UserEntity user = activeUser(7L);
        WechatUserIdentityEntity identity = identity(71L, 7L, "openid-7");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
        when(userService.isActive(user)).thenReturn(true);
        when(identityMapper.selectOne(any())).thenReturn(identity);
        when(identityMapper.deleteById(71L)).thenReturn(1);
        when(userMapper.anonymizeActiveUser(7L, "deleted_user_7", DELETED_AT)).thenReturn(0);

        assertThatThrownBy(() -> transaction.deleteVerified(7L, "openid-7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one active user row to be anonymized");

        verify(dinnerCleanup).removeUser(7L, DELETED_AT);
    }

    @Test
    void repeatedDeletionIsRejectedAsUnauthorized() {
        UserEntity deleted = activeUser(7L);
        deleted.setStatus("DELETED");
        deleted.setDeletedAt(LocalDateTime.parse("2026-07-13T11:00:00"));
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(deleted);
        when(userService.isActive(deleted)).thenReturn(false);

        assertThatThrownBy(() -> transaction.deleteVerified(7L, "openid-7"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(identityMapper, dinnerCleanup);
        verify(userMapper, never()).anonymizeActiveUser(anyLong(), any(), any());
    }

    private UserEntity activeUser(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername("active-user");
        user.setStatus("ACTIVE");
        return user;
    }

    private WechatUserIdentityEntity identity(Long id, Long userId, String openid) {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setId(id);
        identity.setUserId(userId);
        identity.setOpenid(openid);
        return identity;
    }
}

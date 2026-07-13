package com.osheeep.server.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerAccountCleanupService;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionTransaction {

    private final UserMapper userMapper;
    private final UserService userService;
    private final WechatUserIdentityMapper identityMapper;
    private final DinnerAccountCleanupService dinnerCleanup;
    private final Clock clock;

    @Autowired
    public AccountDeletionTransaction(
            UserMapper userMapper,
            UserService userService,
            WechatUserIdentityMapper identityMapper,
            DinnerAccountCleanupService dinnerCleanup
    ) {
        this(userMapper, userService, identityMapper, dinnerCleanup, Clock.systemUTC());
    }

    AccountDeletionTransaction(
            UserMapper userMapper,
            UserService userService,
            WechatUserIdentityMapper identityMapper,
            DinnerAccountCleanupService dinnerCleanup,
            Clock clock
    ) {
        this.userMapper = userMapper;
        this.userService = userService;
        this.identityMapper = identityMapper;
        this.dinnerCleanup = dinnerCleanup;
        this.clock = clock;
    }

    @Transactional
    public void deleteVerified(Long userId, String openid) {
        UserEntity user = userMapper.selectByIdForUpdate(userId);
        if (!userService.isActive(user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not available");
        }
        WechatUserIdentityEntity identity = identityMapper.selectOne(
                Wrappers.<WechatUserIdentityEntity>lambdaQuery()
                        .eq(WechatUserIdentityEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (identity == null || !Objects.equals(identity.getOpenid(), openid)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DELETION_IDENTITY_MISMATCH);
        }

        LocalDateTime deletedAt =
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        identityMapper.deleteById(identity.getId());
        dinnerCleanup.removeUser(userId, deletedAt);
        user.setUsername("deleted_user_" + userId);
        user.setEmail(null);
        user.setPasswordHash(null);
        user.setDisplayName(null);
        user.setAvatarUrl(null);
        user.setStatus("DELETED");
        user.setDeletedAt(deletedAt);
        userMapper.updateById(user);
        userMapper.update(null,
                Wrappers.<UserEntity>update()
                        .eq("id", userId)
                        .set("email", null)
                        .set("password_hash", null)
                        .set("display_name", null)
                        .set("avatar_url", null));
    }
}

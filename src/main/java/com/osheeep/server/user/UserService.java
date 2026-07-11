package com.osheeep.server.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.user.entity.UserEntity;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserEntity findByEmail(String email) {
        return userMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class)
                .eq(UserEntity::getEmail, normalizeEmail(email))
                .isNull(UserEntity::getDeletedAt)
                .last("LIMIT 1"));
    }

    public UserEntity findByUsername(String username) {
        return userMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class)
                .eq(UserEntity::getUsername, username.trim())
                .isNull(UserEntity::getDeletedAt)
                .last("LIMIT 1"));
    }

    public UserEntity createUser(String email, String username, String passwordHash, String displayName) {
        UserEntity user = new UserEntity();
        user.setEmail(normalizeEmail(email));
        user.setUsername(username.trim());
        user.setPasswordHash(passwordHash);
        user.setDisplayName(blankToNull(displayName));
        user.setStatus(ACTIVE_STATUS);
        userMapper.insert(user);
        return user;
    }

    public UserEntity createWechatUser(String username) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setStatus(ACTIVE_STATUS);
        userMapper.insert(user);
        return user;
    }

    public UserEntity requireActiveUser(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (!isActive(user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not available");
        }
        return user;
    }

    public boolean isActive(UserEntity user) {
        return user != null && user.getDeletedAt() == null && ACTIVE_STATUS.equals(user.getStatus());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

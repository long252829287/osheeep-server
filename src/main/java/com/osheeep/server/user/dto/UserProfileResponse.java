package com.osheeep.server.user.dto;

import com.osheeep.server.user.entity.UserEntity;

public record UserProfileResponse(
        Long id,
        String email,
        String username,
        String nickname,
        String avatarUrl
) {

    public static UserProfileResponse from(UserEntity user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getDisplayName(),
                user.getAvatarUrl()
        );
    }
}

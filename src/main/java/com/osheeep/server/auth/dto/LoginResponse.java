package com.osheeep.server.auth.dto;

import com.osheeep.server.user.dto.UserProfileResponse;

public record LoginResponse(
        String accessToken,
        UserProfileResponse user
) {
}

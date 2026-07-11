package com.osheeep.server.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record WechatLoginRequest(@NotBlank String code) {
}

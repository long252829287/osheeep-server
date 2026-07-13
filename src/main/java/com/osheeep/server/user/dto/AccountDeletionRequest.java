package com.osheeep.server.user.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountDeletionRequest(@NotBlank String code) {
}

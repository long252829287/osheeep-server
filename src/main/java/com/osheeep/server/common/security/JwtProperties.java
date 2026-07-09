package com.osheeep.server.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "osheeep.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        long accessTokenTtlMinutes
) {
}

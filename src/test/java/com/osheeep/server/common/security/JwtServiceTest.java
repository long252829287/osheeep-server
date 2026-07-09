package com.osheeep.server.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void generatesAndParsesToken() {
        JwtService jwtService = new JwtService(new JwtProperties("osheeep", SECRET, 120));

        String token = jwtService.generateToken(new CurrentUser(42L, "long"));
        CurrentUser currentUser = jwtService.parseToken(token);

        assertThat(token).isNotBlank();
        assertThat(currentUser.id()).isEqualTo(42L);
        assertThat(currentUser.username()).isEqualTo("long");
    }

    @Test
    void rejectsExpiredToken() {
        JwtService jwtService = new JwtService(new JwtProperties("osheeep", SECRET, -1));
        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMalformedToken() {
        JwtService jwtService = new JwtService(new JwtProperties("osheeep", SECRET, 120));

        assertThatThrownBy(() -> jwtService.parseToken("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }
}

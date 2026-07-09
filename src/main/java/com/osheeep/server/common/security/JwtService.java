package com.osheeep.server.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String USERNAME_CLAIM = "username";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey secretKey;

    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(CurrentUser currentUser) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(properties.accessTokenTtlMinutes()));

        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(currentUser.id().toString())
                .claim(USERNAME_CLAIM, currentUser.username())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public CurrentUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new CurrentUser(Long.valueOf(claims.getSubject()), claims.get(USERNAME_CLAIM, String.class));
    }
}

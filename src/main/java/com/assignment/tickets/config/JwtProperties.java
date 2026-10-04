package com.assignment.tickets.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret HS256 signing key; must be at least 32 bytes (256 bits)
 * @param issuer value of the {@code iss} claim, checked on every request
 * @param ttl    how long an issued token stays valid
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must be at least 32 bytes");
        }
    }
}

package com.devpilot.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;
import com.devpilot.server.settings.service.SystemSettingsService;

@Component
public class JwtTokenProvider {

    private final SecurityProperties properties;
    private final SystemSettingsService settingsService;
    private final SecretKey key;

    public JwtTokenProvider(SecurityProperties properties, SystemSettingsService settingsService) {
        this.properties = properties;
        this.settingsService = settingsService;
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(DevPilotPrincipal principal) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(settingsService.accessTokenTtl());
        return Jwts.builder()
                .subject(principal.userId().toString())
                .claim("username", principal.getUsername())
                .claim("role", principal.roles().isEmpty() ? "" : principal.roles().getFirst())
                .claim("roles", principal.roles())
                .claim("sessionVersion", principal.sessionVersion())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public AccessTokenIdentity parseIdentity(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Object sessionVersion = claims.get("sessionVersion");
            if (!(sessionVersion instanceof Number number)) {
                throw new IllegalArgumentException("Access token has no session version");
            }
            return new AccessTokenIdentity(Long.valueOf(claims.getSubject()), number.longValue());
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidAccessTokenException(exception);
        }
    }

    public record AccessTokenIdentity(Long userId, long sessionVersion) {}

    public long accessTokenTtlSeconds() {
        return settingsService.accessTokenTtl().toSeconds();
    }

    public static class InvalidAccessTokenException extends RuntimeException {
        InvalidAccessTokenException(Throwable cause) {
            super(cause);
        }
    }
}

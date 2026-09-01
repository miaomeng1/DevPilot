package com.devpilot.server.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "devpilot.security")
public record SecurityProperties(
        @NotBlank String jwtSecret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        boolean refreshCookieSecure,
        @NotBlank String refreshCookieName,
        @NotBlank String settingsEncryptionKey) {

    @AssertTrue(message = "devpilot.security.jwt-secret must contain at least 32 bytes")
    public boolean isJwtSecretStrongEnough() {
        return jwtSecret != null && jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32;
    }

    @AssertTrue(message = "devpilot.security.settings-encryption-key must contain at least 32 bytes")
    public boolean isSettingsEncryptionKeyStrongEnough() {
        return settingsEncryptionKey != null
                && settingsEncryptionKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32;
    }
}

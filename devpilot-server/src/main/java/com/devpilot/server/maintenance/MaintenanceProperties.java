package com.devpilot.server.maintenance;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "devpilot.maintenance")
public record MaintenanceProperties(String reportSecret, @NotNull Duration backupFreshness) {

    public boolean reportingConfigured() {
        return reportSecret != null && !reportSecret.isBlank();
    }

    @AssertTrue(message = "devpilot.maintenance.report-secret must be empty or contain at least 32 bytes")
    public boolean isReportSecretStrongEnough() {
        return !reportingConfigured() || reportSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }
}

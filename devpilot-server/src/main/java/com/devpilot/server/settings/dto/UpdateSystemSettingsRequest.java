package com.devpilot.server.settings.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateSystemSettingsRequest(
        @NotBlank @Size(max = 80) String systemName,
        @Size(max = 1000) String logoUrl,
        @NotBlank @Pattern(regexp = "DARK|LIGHT|SYSTEM") String defaultTheme,
        @NotNull @Min(5) @Max(1440) Integer accessTokenTtlMinutes,
        @NotNull @Min(1) @Max(2160) Integer refreshTokenTtlHours,
        @NotNull @Min(15) @Max(600) Integer agentHeartbeatTimeoutSeconds,
        @NotNull @Min(5) @Max(300) Integer metricIntervalSeconds,
        @NotNull @Pattern(regexp = "100|500") String logDefaultLines,
        @NotNull Boolean webhookEnabled,
        @Size(max = 2000) String webhookUrl) {
}

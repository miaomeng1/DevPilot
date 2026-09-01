package com.devpilot.server.settings.dto;

public record SystemSettingsResponse(
        String systemName,
        String logoUrl,
        String defaultTheme,
        int accessTokenTtlMinutes,
        int refreshTokenTtlHours,
        int agentHeartbeatTimeoutSeconds,
        int metricIntervalSeconds,
        int logDefaultLines,
        boolean webhookEnabled,
        boolean webhookConfigured,
        String webhookDestinationType) {
}

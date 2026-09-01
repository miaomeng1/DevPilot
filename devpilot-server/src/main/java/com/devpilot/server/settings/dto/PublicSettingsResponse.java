package com.devpilot.server.settings.dto;

public record PublicSettingsResponse(String systemName, String logoUrl, String defaultTheme, int logDefaultLines) {
}

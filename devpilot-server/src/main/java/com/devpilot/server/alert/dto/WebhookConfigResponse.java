package com.devpilot.server.alert.dto;

public record WebhookConfigResponse(boolean enabled, boolean configured, String destinationType) {
}

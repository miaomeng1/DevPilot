package com.devpilot.server.automation.dto;

import jakarta.validation.constraints.NotNull;

public record AutomationWebhookEnabledRequest(@NotNull Boolean enabled) {
}

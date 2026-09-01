package com.devpilot.server.alert.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WebhookConfigRequest(
        @NotNull Boolean enabled,
        @Size(max = 2000) String url) {
}

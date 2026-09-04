package com.devpilot.server.automation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AutomationWebhookRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 2000) String endpointUrl,
        @NotEmpty @Size(max = 4) List<@Pattern(regexp = "ALERT_FIRING|ALERT_RESOLVED|DEPLOYMENT_HEALTHY|DEPLOYMENT_FAILED") String> eventTypes) {
}

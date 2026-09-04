package com.devpilot.server.automation.dto;

public record CreatedAutomationWebhookResponse(AutomationWebhookResponse subscription, String oneTimeSecret) {
}

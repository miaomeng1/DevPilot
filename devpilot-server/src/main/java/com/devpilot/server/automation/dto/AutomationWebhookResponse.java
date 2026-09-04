package com.devpilot.server.automation.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AutomationWebhookResponse(Long id, String name, String endpointHost, List<String> eventTypes,
                                        boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
}

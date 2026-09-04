package com.devpilot.server.automation.dto;

import java.time.LocalDateTime;

public record AutomationDeliveryResponse(Long id, String eventId, String subscriptionName, String eventType,
                                         String subject, String status, int attemptCount, Integer responseCode,
                                         String errorMessage, LocalDateTime sentAt, LocalDateTime createdAt,
                                         LocalDateTime updatedAt) {
}

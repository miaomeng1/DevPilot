package com.devpilot.server.alert.dto;

import java.time.LocalDateTime;

public record AlertEventResponse(
        String id,
        String ruleId,
        String ruleName,
        String metricType,
        String serverId,
        String serverName,
        String resourceType,
        String resourceId,
        String resourceName,
        String severity,
        String message,
        String status,
        Double currentValue,
        Double threshold,
        String operator,
        LocalDateTime startedAt,
        String acknowledgedBy,
        String acknowledgedByName,
        LocalDateTime acknowledgedAt,
        LocalDateTime resolvedAt,
        LocalDateTime updatedAt,
        String notificationStatus) {
}

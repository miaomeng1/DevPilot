package com.devpilot.server.alert.dto;

import java.time.LocalDateTime;

public record AlertRuleResponse(
        String id,
        String name,
        String metricType,
        String operator,
        Double threshold,
        Integer durationSeconds,
        String severity,
        String serverId,
        String serverName,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

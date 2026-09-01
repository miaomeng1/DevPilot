package com.devpilot.server.audit.dto;

import java.time.LocalDateTime;

public record AuditLogResponse(
        String id,
        String userId,
        String username,
        String action,
        String resourceType,
        String resourceId,
        String resourceName,
        String serverId,
        String serverName,
        String ipAddress,
        String requestParams,
        String result,
        String errorMessage,
        LocalDateTime occurredAt) {
}

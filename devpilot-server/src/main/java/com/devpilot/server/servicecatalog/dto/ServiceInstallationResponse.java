package com.devpilot.server.servicecatalog.dto;

import java.time.LocalDateTime;

public record ServiceInstallationResponse(
        Long id,
        String templateId,
        String templateName,
        String image,
        String displayName,
        String instanceName,
        String environment,
        Long serverId,
        String serverName,
        int requestedPort,
        Integer hostPort,
        String timezone,
        String containerId,
        Long applicationId,
        String status,
        String errorMessage,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) {
}

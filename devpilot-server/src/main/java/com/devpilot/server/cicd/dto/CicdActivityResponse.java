package com.devpilot.server.cicd.dto;

import java.time.LocalDateTime;

public record CicdActivityResponse(
        Long id,
        Long applicationId,
        String applicationName,
        String environment,
        Long serverId,
        String serverName,
        String deploymentKind,
        String provider,
        String imageUri,
        String status,
        String logExcerpt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) {
}

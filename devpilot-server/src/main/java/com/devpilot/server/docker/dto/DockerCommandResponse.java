package com.devpilot.server.docker.dto;

import java.time.LocalDateTime;

public record DockerCommandResponse(
        Long id,
        Long serverId,
        Long containerId,
        String action,
        String status,
        String errorMessage,
        LocalDateTime requestedAt,
        LocalDateTime completedAt) {
}

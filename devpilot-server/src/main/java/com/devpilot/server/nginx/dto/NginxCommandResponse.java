package com.devpilot.server.nginx.dto;

import java.time.LocalDateTime;

public record NginxCommandResponse(
        Long id,
        Long serverId,
        Long configId,
        String filename,
        String action,
        String status,
        String validationOutput,
        String errorMessage,
        LocalDateTime requestedAt,
        LocalDateTime completedAt) {
}

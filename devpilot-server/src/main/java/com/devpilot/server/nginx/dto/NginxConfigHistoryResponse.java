package com.devpilot.server.nginx.dto;

import java.time.LocalDateTime;

public record NginxConfigHistoryResponse(
        Long id,
        Long configId,
        String filename,
        String oldContent,
        String newContent,
        String action,
        Long operatorId,
        String operatorName,
        Long commandId,
        String status,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}

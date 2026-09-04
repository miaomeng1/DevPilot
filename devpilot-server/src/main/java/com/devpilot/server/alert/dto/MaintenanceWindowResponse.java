package com.devpilot.server.alert.dto;

import java.time.LocalDateTime;

public record MaintenanceWindowResponse(
        Long id,
        String name,
        String reason,
        Long serverId,
        String serverName,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String status,
        LocalDateTime createdAt) {
}

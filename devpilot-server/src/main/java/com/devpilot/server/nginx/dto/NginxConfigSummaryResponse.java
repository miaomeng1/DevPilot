package com.devpilot.server.nginx.dto;

import java.time.LocalDateTime;

public record NginxConfigSummaryResponse(
        Long id,
        Long serverId,
        String serverName,
        String filename,
        String contentHash,
        int contentBytes,
        LocalDateTime lastSeenAt,
        LocalDateTime updatedAt) {
}

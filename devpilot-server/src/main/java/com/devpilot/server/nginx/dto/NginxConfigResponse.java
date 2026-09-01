package com.devpilot.server.nginx.dto;

import java.time.LocalDateTime;

public record NginxConfigResponse(
        Long id,
        Long serverId,
        String serverName,
        String filename,
        String content,
        String contentHash,
        LocalDateTime lastSeenAt,
        LocalDateTime updatedAt) {
}

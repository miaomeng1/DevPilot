package com.devpilot.server.nginx.dto;

import java.time.LocalDateTime;

public record NginxHostResponse(
        Long serverId,
        String serverName,
        boolean enabled,
        boolean available,
        String nginxVersion,
        String configPath,
        String errorMessage,
        long configCount,
        LocalDateTime collectedAt) {
}

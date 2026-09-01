package com.devpilot.server.docker.dto;

import java.time.LocalDateTime;

public record DockerOverviewResponse(
        Long serverId,
        boolean available,
        String engineVersion,
        String errorMessage,
        long containers,
        long running,
        long stopped,
        int images,
        int volumes,
        int networks,
        LocalDateTime collectedAt) {
}

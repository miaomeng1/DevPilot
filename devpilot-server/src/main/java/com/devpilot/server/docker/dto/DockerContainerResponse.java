package com.devpilot.server.docker.dto;

import java.time.LocalDateTime;
import java.util.List;

public record DockerContainerResponse(
        Long id,
        Long serverId,
        String containerId,
        String shortId,
        String name,
        String image,
        String state,
        String status,
        String health,
        Double cpuUsage,
        Long memoryUsage,
        Long memoryLimit,
        Long networkRx,
        Long networkTx,
        String ipAddress,
        List<String> ports,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        Integer restartCount,
        String networkMode,
        String composeProject,
        String composeService,
        List<String> volumes,
        List<String> environment,
        LocalDateTime lastSeenAt) {
}

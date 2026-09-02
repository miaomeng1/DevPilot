package com.devpilot.server.application.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ApplicationResponse(
        Long id,
        String name,
        String code,
        String description,
        String environment,
        Long serverId,
        String serverName,
        String deployType,
        Long containerSnapshotId,
        String containerId,
        String containerName,
        String dockerImage,
        String containerIpAddress,
        List<String> ports,
        String currentVersion,
        String accessUrl,
        String healthCheckUrl,
        String status,
        String healthStatus,
        String healthMessage,
        LocalDateTime healthCheckedAt,
        Double cpuUsage,
        Long memoryUsage,
        Long memoryLimit,
        LocalDateTime lastDeployedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

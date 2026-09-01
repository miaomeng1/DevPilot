package com.devpilot.server.application.dto;

import java.time.LocalDateTime;

public record ApplicationDeploymentResponse(
        Long id,
        Long applicationId,
        String applicationName,
        String version,
        Long serverId,
        String serverName,
        String dockerImage,
        Long operatorId,
        String operatorName,
        LocalDateTime deployedAt,
        String result,
        String logs) {
}

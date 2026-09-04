package com.devpilot.server.cicd.dto;

import java.util.List;

public record CicdPromotionTargetResponse(
        Long applicationId,
        String applicationName,
        String environment,
        Long serverId,
        String serverName,
        String accessUrl,
        boolean ready,
        List<String> blockers,
        String currentHealthyImage) {
}

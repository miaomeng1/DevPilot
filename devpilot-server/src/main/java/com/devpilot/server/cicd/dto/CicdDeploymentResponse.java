package com.devpilot.server.cicd.dto;

import java.time.LocalDateTime;

public record CicdDeploymentResponse(
        Long id,
        Long applicationId,
        Long pipelineRunId,
        Long rollbackOfId,
        Long promotedFromApplicationId,
        Long promotedFromDeploymentId,
        String deploymentKind,
        String provider,
        String imageUri,
        String previousImageUri,
        String status,
        String providerDeploymentId,
        String logs,
        Long triggeredBy,
        LocalDateTime startedAt,
        LocalDateTime healthDeadlineAt,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) {
}

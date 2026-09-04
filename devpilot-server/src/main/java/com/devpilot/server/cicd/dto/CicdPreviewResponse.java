package com.devpilot.server.cicd.dto;

import java.time.LocalDateTime;

public record CicdPreviewResponse(
        Long id,
        Long applicationId,
        Integer pullRequestId,
        String externalRunId,
        String title,
        String branchName,
        String commitSha,
        String imageUri,
        String previewUrl,
        String provider,
        String providerDeploymentId,
        String status,
        String runUrl,
        String failureReason,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt) {
}

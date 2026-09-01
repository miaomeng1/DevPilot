package com.devpilot.server.cicd.dto;

import java.time.LocalDateTime;

public record PipelineRunResponse(
        Long id,
        Long applicationId,
        String externalRunId,
        String commitSha,
        String branchName,
        String status,
        String testStatus,
        String securityStatus,
        String imageUri,
        String imageDigest,
        String runUrl,
        String summary,
        String deployStatus,
        String deployError,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) {
}


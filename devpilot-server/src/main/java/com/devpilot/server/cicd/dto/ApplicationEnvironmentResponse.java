package com.devpilot.server.cicd.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ApplicationEnvironmentResponse(
        Long applicationId,
        int revision,
        Integer syncedRevision,
        List<ApplicationEnvironmentVariableResponse> variables,
        String syncStatus,
        String syncError,
        LocalDateTime providerSyncedAt,
        LocalDateTime updatedAt) {
}

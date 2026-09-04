package com.devpilot.server.maintenance.dto;

import java.time.LocalDateTime;

public record BackupReportResponse(
        Long id,
        String fileName,
        Long sizeBytes,
        String sha256,
        String destinationType,
        LocalDateTime createdAt,
        LocalDateTime verifiedAt,
        LocalDateTime reportedAt) {
}

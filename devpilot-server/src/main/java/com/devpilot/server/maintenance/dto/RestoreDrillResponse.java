package com.devpilot.server.maintenance.dto;

import java.time.LocalDateTime;

public record RestoreDrillResponse(
        Long id,
        Long backupReportId,
        String backupFileName,
        String environment,
        String result,
        String notes,
        Long performedBy,
        String performedByName,
        LocalDateTime performedAt) {
}

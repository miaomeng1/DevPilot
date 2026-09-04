package com.devpilot.server.maintenance.dto;

import java.util.List;

public record BackupOverviewResponse(
        boolean reportingConfigured,
        String state,
        long freshnessHours,
        Long ageHours,
        BackupReportResponse latest,
        RestoreDrillResponse latestDrill,
        List<BackupReportResponse> reports) {
}

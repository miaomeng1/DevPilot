package com.devpilot.server.maintenance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RestoreDrillRequest(
        @NotNull Long backupReportId,
        @NotNull @Pattern(regexp = "ISOLATED|STAGING") String environment,
        @NotNull @Pattern(regexp = "PASSED|FAILED") String result,
        @Size(max = 1000) String notes) {
}

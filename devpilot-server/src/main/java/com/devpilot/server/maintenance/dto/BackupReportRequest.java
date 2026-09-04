package com.devpilot.server.maintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

public record BackupReportRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,255}") String fileName,
        @NotNull @Positive Long sizeBytes,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256,
        @NotBlank @Pattern(regexp = "LOCAL|S3|RCLONE") String destinationType,
        @NotNull LocalDateTime createdAt) {
}

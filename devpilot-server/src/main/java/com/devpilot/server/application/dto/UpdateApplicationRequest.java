package com.devpilot.server.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateApplicationRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @NotBlank @Pattern(regexp = "DEV|TEST|STAGING|PRODUCTION") String environment,
        @NotNull Long serverId,
        @NotNull Long containerSnapshotId,
        @Size(max = 120) String currentVersion,
        @Size(max = 1000) String healthCheckUrl,
        @Size(max = 1000) String accessUrl) {
}

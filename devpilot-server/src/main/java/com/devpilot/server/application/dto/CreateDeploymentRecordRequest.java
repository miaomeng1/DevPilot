package com.devpilot.server.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDeploymentRecordRequest(
        @NotBlank @Size(max = 120) String version,
        @NotBlank @Size(max = 500) String dockerImage,
        @NotBlank @Pattern(regexp = "SUCCESS|FAILED") String result,
        @Size(max = 10000) String logs) {
}

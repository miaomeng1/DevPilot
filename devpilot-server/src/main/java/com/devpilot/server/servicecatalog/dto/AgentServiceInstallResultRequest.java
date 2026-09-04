package com.devpilot.server.servicecatalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentServiceInstallResultRequest(
        @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
        @Size(max = 64) String containerId,
        @Size(max = 1000) String errorMessage) {
}

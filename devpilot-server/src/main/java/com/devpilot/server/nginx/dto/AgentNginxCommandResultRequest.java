package com.devpilot.server.nginx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentNginxCommandResultRequest(
        @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
        @Size(max = 10000) String validationOutput,
        @Size(max = 2000) String errorMessage) {
}

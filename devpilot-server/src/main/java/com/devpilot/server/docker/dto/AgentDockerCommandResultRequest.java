package com.devpilot.server.docker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentDockerCommandResultRequest(
        @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
        @Size(max = 1000) String errorMessage) {
}

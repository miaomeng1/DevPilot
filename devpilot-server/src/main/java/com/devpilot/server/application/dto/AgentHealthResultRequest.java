package com.devpilot.server.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentHealthResultRequest(
        @NotBlank @Pattern(regexp = "HEALTHY|UNHEALTHY") String status,
        @Min(0) int latencyMillis,
        Integer httpStatus,
        @Size(max = 500) String message) {
}

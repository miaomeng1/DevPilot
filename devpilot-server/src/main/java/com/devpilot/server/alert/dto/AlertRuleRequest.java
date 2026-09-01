package com.devpilot.server.alert.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AlertRuleRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "SERVER_CPU|SERVER_MEMORY|SERVER_DISK|AGENT_OFFLINE|CONTAINER_STOPPED|APP_UNHEALTHY")
        String metricType,
        @NotBlank @Pattern(regexp = "GT|GTE|LT|LTE|EQ|NE") String operator,
        Double threshold,
        @NotNull @Min(0) @Max(86400) Integer durationSeconds,
        @NotBlank @Pattern(regexp = "INFO|WARNING|CRITICAL") String severity,
        Long serverId,
        @NotNull Boolean enabled) {
}

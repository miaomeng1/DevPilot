package com.devpilot.server.docker.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record AgentDockerContainerSnapshot(
        @NotBlank @Size(max = 64) String containerId,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 500) String image,
        @NotBlank @Size(max = 32) String state,
        @Size(max = 255) String status,
        @Size(max = 32) String health,
        @NotNull @DecimalMin("0") @DecimalMax("10000") Double cpuUsage,
        @NotNull @PositiveOrZero Long memoryUsage,
        @NotNull @PositiveOrZero Long memoryLimit,
        @NotNull @PositiveOrZero Long networkRx,
        @NotNull @PositiveOrZero Long networkTx,
        @Size(max = 64) String ipAddress,
        @NotNull @Size(max = 256) List<@Size(max = 200) String> ports,
        Instant createdAt,
        Instant startedAt,
        @NotNull @PositiveOrZero Integer restartCount,
        @Size(max = 128) String networkMode,
        @Size(max = 255) String composeProject,
        @Size(max = 255) String composeService,
        @NotNull @Size(max = 256) List<@Size(max = 1000) String> volumes,
        @NotNull @Size(max = 512) List<@Size(max = 2048) String> environment) {
}

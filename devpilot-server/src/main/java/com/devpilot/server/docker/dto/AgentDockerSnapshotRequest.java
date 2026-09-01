package com.devpilot.server.docker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record AgentDockerSnapshotRequest(
        @Size(max = 32) String agentVersion,
        boolean available,
        @Size(max = 64) String engineVersion,
        @Size(max = 500) String errorMessage,
        @PositiveOrZero int images,
        @PositiveOrZero int volumes,
        @PositiveOrZero int networks,
        @NotNull Instant collectedAt,
        @NotNull @Size(max = 500) List<@Valid AgentDockerContainerSnapshot> containers) {
}

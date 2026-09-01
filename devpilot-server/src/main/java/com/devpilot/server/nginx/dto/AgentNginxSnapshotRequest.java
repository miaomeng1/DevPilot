package com.devpilot.server.nginx.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record AgentNginxSnapshotRequest(
        @NotBlank @Size(max = 64) String agentVersion,
        boolean enabled,
        boolean available,
        @Size(max = 120) String nginxVersion,
        @Size(max = 1000) String configPath,
        @Size(max = 1000) String errorMessage,
        @NotNull Instant collectedAt,
        @NotNull @Size(max = 500) List<@Valid AgentNginxFileSnapshot> files) {
}

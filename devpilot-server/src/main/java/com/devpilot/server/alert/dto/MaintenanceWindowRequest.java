package com.devpilot.server.alert.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MaintenanceWindowRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String reason,
        Long serverId,
        @NotNull Instant startsAt,
        @NotNull @Future Instant endsAt) {
}

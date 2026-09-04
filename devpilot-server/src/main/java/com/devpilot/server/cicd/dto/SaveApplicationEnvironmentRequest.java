package com.devpilot.server.cicd.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveApplicationEnvironmentRequest(
        @NotNull @PositiveOrZero Integer expectedRevision,
        @NotNull @Size(max = 200) List<@Valid ApplicationEnvironmentVariableRequest> variables) {
}

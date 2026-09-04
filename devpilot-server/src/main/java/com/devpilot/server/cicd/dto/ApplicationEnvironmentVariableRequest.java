package com.devpilot.server.cicd.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApplicationEnvironmentVariableRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String key,
        @Size(max = 8000) String value,
        @NotNull Boolean secret,
        @Size(max = 255) String description) {
}

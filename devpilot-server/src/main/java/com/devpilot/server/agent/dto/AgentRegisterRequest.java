package com.devpilot.server.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AgentRegisterRequest(
        @NotBlank @Size(min = 32, max = 128) String token,
        @NotBlank @Size(max = 255) String hostname,
        @NotBlank @Size(max = 64) String ip,
        @NotBlank @Size(max = 128) String os,
        @NotBlank @Size(max = 128) String kernel,
        @NotBlank @Size(max = 64) String arch,
        @NotBlank @Size(max = 32) String agentVersion,
        @Size(max = 255) String cpuModel,
        @NotNull @Min(1) @Max(4096) Integer cpuCores,
        @NotNull @PositiveOrZero Long memoryTotal,
        @NotNull @PositiveOrZero Long diskTotal) {
}


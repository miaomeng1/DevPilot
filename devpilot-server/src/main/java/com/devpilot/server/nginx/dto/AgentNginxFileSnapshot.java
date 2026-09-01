package com.devpilot.server.nginx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentNginxFileSnapshot(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*\\.conf") String filename,
        @NotBlank @Size(max = 262144) String content,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String contentHash) {
}

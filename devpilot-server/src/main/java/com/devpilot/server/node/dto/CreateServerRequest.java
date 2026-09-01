package com.devpilot.server.node.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServerRequest(
        @NotBlank @Size(min = 2, max = 100) String name) {
}


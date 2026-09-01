package com.devpilot.server.docker.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateLogTicketRequest(
        @Min(100) @Max(500) int lines,
        boolean follow) {
}

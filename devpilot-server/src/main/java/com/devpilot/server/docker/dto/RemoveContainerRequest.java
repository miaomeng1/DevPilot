package com.devpilot.server.docker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RemoveContainerRequest(@NotBlank @Size(max = 255) String confirmName) {
}

package com.devpilot.server.nginx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNginxConfigRequest(@NotBlank @Size(max = 262144) String content) {
}

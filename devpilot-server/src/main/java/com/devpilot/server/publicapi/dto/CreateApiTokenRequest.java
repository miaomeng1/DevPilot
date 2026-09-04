package com.devpilot.server.publicapi.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record CreateApiTokenRequest(
        @NotBlank @Size(max = 120) String name,
        @Future LocalDateTime expiresAt) {
}

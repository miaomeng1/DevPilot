package com.devpilot.server.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotBlank @Size(max = 100) String displayName,
        @Email @Size(max = 190) String email,
        @NotBlank @Pattern(regexp = "ADMIN|DEVELOPER|VIEWER") String role,
        @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {
}

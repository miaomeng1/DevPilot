package com.devpilot.server.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 32)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "只能包含字母、数字、点、下划线和连字符") String username,
        @NotBlank @Size(max = 100) String displayName,
        @Email @Size(max = 190) String email,
        @NotBlank @Pattern(regexp = "ADMIN|DEVELOPER|VIEWER") String role,
        @NotBlank @Size(min = 12, max = 128)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "必须同时包含字母和数字") String password,
        @NotBlank @Size(max = 128) String confirmPassword) {
}

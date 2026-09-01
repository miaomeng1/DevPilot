package com.devpilot.server.user.dto;

import java.time.LocalDateTime;

public record UserResponse(
        String id,
        String username,
        String displayName,
        String email,
        String role,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

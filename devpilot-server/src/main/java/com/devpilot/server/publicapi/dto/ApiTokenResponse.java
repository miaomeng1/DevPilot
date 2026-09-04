package com.devpilot.server.publicapi.dto;

import java.time.LocalDateTime;

public record ApiTokenResponse(Long id, String name, String prefix, String scope, String status,
                               LocalDateTime expiresAt, LocalDateTime lastUsedAt,
                               LocalDateTime revokedAt, LocalDateTime createdAt) {
}

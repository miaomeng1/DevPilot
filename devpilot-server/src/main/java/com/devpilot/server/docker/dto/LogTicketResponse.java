package com.devpilot.server.docker.dto;

import java.time.LocalDateTime;

public record LogTicketResponse(String webSocketPath, LocalDateTime expiresAt) {
}

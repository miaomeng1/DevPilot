package com.devpilot.server.nginx.dto;

public record AgentNginxCommandResponse(
        Long commandId,
        String action,
        String filename,
        String content) {
}

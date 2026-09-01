package com.devpilot.server.application.dto;

public record AgentHealthTaskResponse(Long applicationId, String healthCheckUrl, int timeoutSeconds) {
}

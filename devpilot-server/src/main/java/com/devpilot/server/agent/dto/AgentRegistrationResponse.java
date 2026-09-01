package com.devpilot.server.agent.dto;

import java.time.LocalDateTime;

public record AgentRegistrationResponse(
        Long serverId,
        String serverName,
        long heartbeatIntervalSeconds,
        int metricIntervalSeconds,
        LocalDateTime registeredAt) {
}

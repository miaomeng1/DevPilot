package com.devpilot.server.agent.dto;

import java.time.LocalDateTime;

public record AgentHeartbeatResponse(
        Long serverId,
        String status,
        long nextHeartbeatSeconds,
        int metricIntervalSeconds,
        LocalDateTime serverTime) {
}

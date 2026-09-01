package com.devpilot.server.agent.dto;

import jakarta.validation.constraints.Size;

public record AgentHeartbeatRequest(
        @Size(max = 32) String agentVersion) {
}


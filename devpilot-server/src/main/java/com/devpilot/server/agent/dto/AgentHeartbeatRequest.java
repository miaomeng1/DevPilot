package com.devpilot.server.agent.dto;

import jakarta.validation.constraints.Size;

public record AgentHeartbeatRequest(
        @Size(max = 32) String agentVersion,
        @Size(max = 65536) java.util.List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(65535) Integer> listeningTcpPorts) {
    public AgentHeartbeatRequest(String agentVersion) { this(agentVersion, null); }
}

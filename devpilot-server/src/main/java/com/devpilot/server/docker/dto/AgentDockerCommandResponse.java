package com.devpilot.server.docker.dto;

public record AgentDockerCommandResponse(
        Long commandId,
        String containerId,
        String action,
        String logSessionId,
        Integer lines,
        Boolean follow) {
}

package com.devpilot.server.node.dto;

public record CreateServerResponse(
        ServerNodeResponse server,
        String agentToken,
        String installCommand) {
}


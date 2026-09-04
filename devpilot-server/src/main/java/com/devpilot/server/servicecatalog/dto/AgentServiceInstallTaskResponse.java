package com.devpilot.server.servicecatalog.dto;

public record AgentServiceInstallTaskResponse(
        Long installationId,
        String templateId,
        String instanceName,
        int hostPort,
        String timezone) {
}

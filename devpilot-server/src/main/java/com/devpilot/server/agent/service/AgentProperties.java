package com.devpilot.server.agent.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "devpilot.agent")
public record AgentProperties(
        @NotNull Duration heartbeatInterval,
        @NotNull Duration heartbeatTimeout,
        @NotBlank String publicUrl) {
}


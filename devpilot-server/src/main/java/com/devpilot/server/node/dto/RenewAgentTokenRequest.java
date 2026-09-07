package com.devpilot.server.node.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RenewAgentTokenRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String requestId,
        @NotBlank @Size(max = 64) String expectedRevision,
        @AssertTrue(message = "请确认撤销旧 Token 并更新目标机 Agent 配置") boolean confirmed) { }

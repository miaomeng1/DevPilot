package com.devpilot.server.agent.controller;

import com.devpilot.server.agent.dto.AgentHeartbeatRequest;
import com.devpilot.server.agent.dto.AgentHeartbeatResponse;
import com.devpilot.server.agent.dto.AgentRegisterRequest;
import com.devpilot.server.agent.dto.AgentRegistrationResponse;
import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    public static final String AGENT_TOKEN_HEADER = "X-DevPilot-Agent-Token";

    private final AgentRegistrationService registrationService;

    @PostMapping("/register")
    public ApiResponse<AgentRegistrationResponse> register(@Valid @RequestBody AgentRegisterRequest request) {
        return ApiResponse.success(registrationService.register(request));
    }

    @PostMapping("/heartbeat")
    public ApiResponse<AgentHeartbeatResponse> heartbeat(
            @RequestHeader(name = AGENT_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody AgentHeartbeatRequest request) {
        return ApiResponse.success(registrationService.heartbeat(token, request));
    }
}


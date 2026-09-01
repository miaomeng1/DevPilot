package com.devpilot.server.application.controller;

import com.devpilot.server.agent.controller.AgentController;
import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.application.dto.AgentHealthResultRequest;
import com.devpilot.server.application.dto.AgentHealthTaskResponse;
import com.devpilot.server.application.service.ApplicationService;
import com.devpilot.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/applications/health")
@RequiredArgsConstructor
public class AgentApplicationController {

    private final AgentRegistrationService registrationService;
    private final ApplicationService applicationService;

    @GetMapping("/next")
    public ApiResponse<AgentHealthTaskResponse> next(
            @RequestHeader(AgentController.AGENT_TOKEN_HEADER) String token) {
        return ApiResponse.success(applicationService.claimHealthCheck(registrationService.authenticateActive(token)));
    }

    @PostMapping("/{applicationId}/result")
    public ApiResponse<Void> result(@RequestHeader(AgentController.AGENT_TOKEN_HEADER) String token,
                                    @PathVariable Long applicationId,
                                    @Valid @RequestBody AgentHealthResultRequest request) {
        applicationService.recordHealthResult(registrationService.authenticateActive(token), applicationId, request);
        return ApiResponse.success(null);
    }
}

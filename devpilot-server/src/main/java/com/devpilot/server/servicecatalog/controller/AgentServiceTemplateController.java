package com.devpilot.server.servicecatalog.controller;

import com.devpilot.server.agent.controller.AgentController;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.servicecatalog.dto.AgentServiceInstallResultRequest;
import com.devpilot.server.servicecatalog.dto.AgentServiceInstallTaskResponse;
import com.devpilot.server.servicecatalog.service.ServiceTemplateService;
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
@RequestMapping("/api/agent/service-templates")
@RequiredArgsConstructor
public class AgentServiceTemplateController {

    private final ServiceTemplateService service;

    @GetMapping("/installations/next")
    public ApiResponse<AgentServiceInstallTaskResponse> next(
            @RequestHeader(name = AgentController.AGENT_TOKEN_HEADER, required = false) String token) {
        return ApiResponse.success(service.claimNext(token));
    }

    @PostMapping("/installations/{installationId}/result")
    public ApiResponse<Void> complete(
            @RequestHeader(name = AgentController.AGENT_TOKEN_HEADER, required = false) String token,
            @PathVariable Long installationId,
            @Valid @RequestBody AgentServiceInstallResultRequest request) {
        service.complete(token, installationId, request);
        return ApiResponse.success(null);
    }
}

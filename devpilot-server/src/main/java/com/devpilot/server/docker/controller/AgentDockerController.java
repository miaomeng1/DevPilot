package com.devpilot.server.docker.controller;

import com.devpilot.server.agent.controller.AgentController;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.docker.dto.AgentDockerCommandResponse;
import com.devpilot.server.docker.dto.AgentDockerCommandResultRequest;
import com.devpilot.server.docker.dto.AgentDockerSnapshotRequest;
import com.devpilot.server.docker.service.DockerCommandService;
import com.devpilot.server.docker.service.DockerInventoryService;
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
@RequestMapping("/api/agent/docker")
@RequiredArgsConstructor
public class AgentDockerController {

    private final DockerInventoryService inventoryService;
    private final DockerCommandService commandService;

    @PostMapping("/snapshot")
    public ApiResponse<Long> snapshot(
            @RequestHeader(name = AgentController.AGENT_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody AgentDockerSnapshotRequest request) {
        return ApiResponse.success(inventoryService.ingest(token, request));
    }

    @GetMapping("/commands/next")
    public ApiResponse<AgentDockerCommandResponse> next(
            @RequestHeader(name = AgentController.AGENT_TOKEN_HEADER, required = false) String token) {
        return ApiResponse.success(commandService.claimNext(token));
    }

    @PostMapping("/commands/{commandId}/result")
    public ApiResponse<Void> complete(
            @RequestHeader(name = AgentController.AGENT_TOKEN_HEADER, required = false) String token,
            @PathVariable Long commandId,
            @Valid @RequestBody AgentDockerCommandResultRequest request) {
        commandService.complete(token, commandId, request);
        return ApiResponse.success(null);
    }
}

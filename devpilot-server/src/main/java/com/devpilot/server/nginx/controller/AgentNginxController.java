package com.devpilot.server.nginx.controller;

import com.devpilot.server.agent.controller.AgentController;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.nginx.dto.AgentNginxCommandResponse;
import com.devpilot.server.nginx.dto.AgentNginxCommandResultRequest;
import com.devpilot.server.nginx.dto.AgentNginxSnapshotRequest;
import com.devpilot.server.nginx.service.NginxCommandService;
import com.devpilot.server.nginx.service.NginxInventoryService;
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
@RequestMapping("/api/agent/nginx")
@RequiredArgsConstructor
public class AgentNginxController {

    private final NginxInventoryService inventoryService;
    private final NginxCommandService commandService;

    @PostMapping("/snapshot")
    public ApiResponse<Long> snapshot(@RequestHeader(AgentController.AGENT_TOKEN_HEADER) String token,
                                      @Valid @RequestBody AgentNginxSnapshotRequest request) {
        return ApiResponse.success(inventoryService.ingest(token, request));
    }

    @GetMapping("/commands/next")
    public ApiResponse<AgentNginxCommandResponse> next(
            @RequestHeader(AgentController.AGENT_TOKEN_HEADER) String token) {
        return ApiResponse.success(commandService.claimNext(token));
    }

    @PostMapping("/commands/{id}/result")
    public ApiResponse<Void> result(@RequestHeader(AgentController.AGENT_TOKEN_HEADER) String token,
                                    @PathVariable Long id,
                                    @Valid @RequestBody AgentNginxCommandResultRequest request) {
        commandService.complete(token, id, request);
        return ApiResponse.success(null);
    }
}

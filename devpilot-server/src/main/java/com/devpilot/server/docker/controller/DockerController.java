package com.devpilot.server.docker.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.docker.dto.DockerCommandResponse;
import com.devpilot.server.docker.dto.DockerContainerResponse;
import com.devpilot.server.docker.dto.DockerOverviewResponse;
import com.devpilot.server.docker.dto.RemoveContainerRequest;
import com.devpilot.server.docker.dto.CreateLogTicketRequest;
import com.devpilot.server.docker.dto.LogTicketResponse;
import com.devpilot.server.docker.service.DockerCommandService;
import com.devpilot.server.docker.service.DockerInventoryService;
import com.devpilot.server.docker.service.LogRelayService;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/docker")
@RequiredArgsConstructor
public class DockerController {

    private final DockerInventoryService inventoryService;
    private final DockerCommandService commandService;
    private final LogRelayService logRelayService;

    @GetMapping("/overview")
    public ApiResponse<DockerOverviewResponse> overview(@RequestParam(required = false) Long serverId) {
        return ApiResponse.success(inventoryService.overview(serverId));
    }

    @GetMapping("/containers")
    public ApiResponse<List<DockerContainerResponse>> containers(@RequestParam(required = false) Long serverId) {
        return ApiResponse.success(inventoryService.list(serverId));
    }

    @GetMapping("/containers/{id}")
    public ApiResponse<DockerContainerResponse> container(@PathVariable Long id) {
        return ApiResponse.success(inventoryService.get(id));
    }

    @PostMapping("/containers/{id}/{action:start|stop|restart}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<ApiResponse<DockerCommandResponse>> operate(
            @PathVariable Long id, @PathVariable String action,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(commandService.enqueue(id, action, null, principal)));
    }

    @PostMapping("/containers/{id}/remove")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DockerCommandResponse>> remove(
            @PathVariable Long id, @Valid @RequestBody RemoveContainerRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(commandService.enqueue(id, "REMOVE", request.confirmName(), principal)));
    }

    @GetMapping("/commands/{id}")
    public ApiResponse<DockerCommandResponse> command(@PathVariable Long id) {
        return ApiResponse.success(commandService.get(id));
    }

    @PostMapping("/containers/{id}/logs/ticket")
    public ApiResponse<LogTicketResponse> logTicket(
            @PathVariable Long id, @Valid @RequestBody CreateLogTicketRequest request) {
        int lines = request.lines() == 500 ? 500 : 100;
        return ApiResponse.success(logRelayService.createTicket(id, lines, request.follow()));
    }
}

package com.devpilot.server.nginx.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.nginx.dto.NginxCommandResponse;
import com.devpilot.server.nginx.dto.NginxConfigHistoryResponse;
import com.devpilot.server.nginx.dto.NginxConfigResponse;
import com.devpilot.server.nginx.dto.NginxConfigSummaryResponse;
import com.devpilot.server.nginx.dto.NginxHostResponse;
import com.devpilot.server.nginx.dto.UpdateNginxConfigRequest;
import com.devpilot.server.nginx.service.NginxCommandService;
import com.devpilot.server.nginx.service.NginxInventoryService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nginx")
@RequiredArgsConstructor
public class NginxController {

    private final NginxInventoryService inventoryService;
    private final NginxCommandService commandService;

    @GetMapping("/hosts")
    public ApiResponse<List<NginxHostResponse>> hosts() {
        return ApiResponse.success(inventoryService.hosts());
    }

    @GetMapping("/hosts/{serverId}")
    public ApiResponse<NginxHostResponse> host(@PathVariable Long serverId) {
        return ApiResponse.success(inventoryService.host(serverId));
    }

    @GetMapping("/configs")
    public ApiResponse<List<NginxConfigSummaryResponse>> configs(@RequestParam(required = false) Long serverId) {
        return ApiResponse.success(inventoryService.list(serverId));
    }

    @GetMapping("/configs/{id}")
    public ApiResponse<NginxConfigResponse> config(@PathVariable Long id) {
        return ApiResponse.success(inventoryService.get(id));
    }

    @PutMapping("/configs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<ApiResponse<NginxCommandResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateNginxConfigRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(commandService.update(id, request.content(), principal)));
    }

    @GetMapping("/configs/{id}/history")
    public ApiResponse<List<NginxConfigHistoryResponse>> history(@PathVariable Long id) {
        return ApiResponse.success(commandService.history(id));
    }

    @PostMapping("/configs/{id}/history/{historyId}/rollback")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<ApiResponse<NginxCommandResponse>> rollback(
            @PathVariable Long id, @PathVariable Long historyId,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(commandService.rollback(id, historyId, principal)));
    }

    @GetMapping("/commands/{id}")
    public ApiResponse<NginxCommandResponse> command(@PathVariable Long id) {
        return ApiResponse.success(commandService.get(id));
    }
}

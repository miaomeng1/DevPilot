package com.devpilot.server.node.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.node.dto.CreateServerRequest;
import com.devpilot.server.node.dto.CreateServerResponse;
import com.devpilot.server.node.dto.ServerNodeResponse;
import com.devpilot.server.node.service.ServerNodeService;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
public class ServerNodeController {

    private final ServerNodeService serverNodeService;

    @GetMapping
    public ApiResponse<List<ServerNodeResponse>> list() {
        return ApiResponse.success(serverNodeService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ServerNodeResponse> get(@PathVariable Long id) {
        return ApiResponse.success(serverNodeService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CreateServerResponse>> create(
            @Valid @RequestBody CreateServerRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(serverNodeService.create(request, principal)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        serverNodeService.delete(id);
        return ApiResponse.success(null);
    }
}

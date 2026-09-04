package com.devpilot.server.publicapi.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.publicapi.dto.ApiTokenResponse;
import com.devpilot.server.publicapi.dto.CreateApiTokenRequest;
import com.devpilot.server.publicapi.dto.CreatedApiTokenResponse;
import com.devpilot.server.publicapi.service.ApiAccessTokenService;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/api-tokens")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ApiTokenController {
    private final ApiAccessTokenService service;

    @GetMapping public ApiResponse<List<ApiTokenResponse>> list() { return ApiResponse.success(service.list()); }

    @PostMapping
    public ApiResponse<CreatedApiTokenResponse> create(@Valid @RequestBody CreateApiTokenRequest request,
                                                       @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(service.create(request, principal));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        service.revoke(id);
        return ApiResponse.success(null);
    }
}

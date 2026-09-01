package com.devpilot.server.application.controller;

import com.devpilot.server.application.dto.ApplicationDeploymentResponse;
import com.devpilot.server.application.dto.ApplicationResponse;
import com.devpilot.server.application.dto.CreateApplicationRequest;
import com.devpilot.server.application.dto.CreateDeploymentRecordRequest;
import com.devpilot.server.application.dto.UpdateApplicationRequest;
import com.devpilot.server.application.service.ApplicationService;
import com.devpilot.server.common.ApiResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @GetMapping
    public ApiResponse<List<ApplicationResponse>> list() {
        return ApiResponse.success(applicationService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationResponse> get(@PathVariable Long id) {
        return ApiResponse.success(applicationService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ApiResponse<ApplicationResponse> create(@Valid @RequestBody CreateApplicationRequest request,
                                                   @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(applicationService.create(request, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ApiResponse<ApplicationResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody UpdateApplicationRequest request) {
        return ApiResponse.success(applicationService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/deployments")
    public ApiResponse<List<ApplicationDeploymentResponse>> deployments(@PathVariable Long id) {
        return ApiResponse.success(applicationService.deployments(id));
    }

    @PostMapping("/{id}/deployments")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ApiResponse<ApplicationDeploymentResponse> recordDeployment(
            @PathVariable Long id, @Valid @RequestBody CreateDeploymentRecordRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(applicationService.recordDeployment(id, request, principal));
    }
}

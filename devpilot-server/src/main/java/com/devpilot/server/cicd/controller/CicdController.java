package com.devpilot.server.cicd.controller;

import com.devpilot.server.cicd.dto.CicdConfigurationRequest;
import com.devpilot.server.cicd.dto.CicdConfigurationResponse;
import com.devpilot.server.cicd.dto.CicdActivityResponse;
import com.devpilot.server.cicd.dto.CicdDeploymentResponse;
import com.devpilot.server.cicd.dto.CicdReadinessResponse;
import com.devpilot.server.cicd.dto.CicdPromotionRequest;
import com.devpilot.server.cicd.dto.CicdPromotionTargetResponse;
import com.devpilot.server.cicd.dto.PipelineRunResponse;
import com.devpilot.server.cicd.dto.ApplicationEnvironmentResponse;
import com.devpilot.server.cicd.dto.SaveApplicationEnvironmentRequest;
import com.devpilot.server.cicd.service.ApplicationEnvironmentService;
import com.devpilot.server.cicd.service.CicdDeploymentService;
import com.devpilot.server.cicd.service.CicdReadinessService;
import com.devpilot.server.cicd.service.CicdService;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cicd")
@RequiredArgsConstructor
public class CicdController {
    private final CicdService cicdService;
    private final CicdDeploymentService deploymentService;
    private final ApplicationEnvironmentService environmentService;
    private final CicdReadinessService readinessService;

    @GetMapping("/configurations/{applicationId}")
    public ApiResponse<CicdConfigurationResponse> configuration(@PathVariable Long applicationId) {
        return ApiResponse.success(cicdService.getConfiguration(applicationId));
    }

    @PutMapping("/configurations/{applicationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CicdConfigurationResponse> saveConfiguration(
            @PathVariable Long applicationId,
            @Valid @RequestBody CicdConfigurationRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(cicdService.saveConfiguration(applicationId, request, principal));
    }

    @GetMapping("/applications/{applicationId}/runs")
    public ApiResponse<List<PipelineRunResponse>> runs(@PathVariable Long applicationId) {
        return ApiResponse.success(cicdService.listRuns(applicationId));
    }

    @GetMapping("/applications/{applicationId}/deployments")
    public ApiResponse<List<CicdDeploymentResponse>> deployments(@PathVariable Long applicationId) {
        return ApiResponse.success(deploymentService.list(applicationId));
    }

    @GetMapping("/applications/{applicationId}/readiness")
    public ApiResponse<CicdReadinessResponse> readiness(@PathVariable Long applicationId) {
        return ApiResponse.success(readinessService.inspect(applicationId));
    }

    @GetMapping("/applications/{applicationId}/promotion-targets")
    public ApiResponse<List<CicdPromotionTargetResponse>> promotionTargets(@PathVariable Long applicationId) {
        return ApiResponse.success(deploymentService.promotionTargets(applicationId));
    }

    @GetMapping("/applications/{applicationId}/environment")
    public ApiResponse<ApplicationEnvironmentResponse> environment(@PathVariable Long applicationId) {
        return ApiResponse.success(environmentService.get(applicationId));
    }

    @PutMapping("/applications/{applicationId}/environment")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ApplicationEnvironmentResponse> saveEnvironment(
            @PathVariable Long applicationId,
            @Valid @RequestBody SaveApplicationEnvironmentRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(environmentService.save(applicationId, request, principal));
    }

    @PostMapping("/applications/{applicationId}/environment/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ApplicationEnvironmentResponse> syncEnvironment(@PathVariable Long applicationId) {
        return ApiResponse.success(environmentService.sync(applicationId));
    }

    @GetMapping("/activity")
    public ApiResponse<List<CicdActivityResponse>> activity(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(deploymentService.activity(limit));
    }

    @PostMapping("/applications/{applicationId}/deployments/{deploymentId}/rollback")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ApiResponse<CicdDeploymentResponse> rollback(
            @PathVariable Long applicationId,
            @PathVariable Long deploymentId,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(deploymentService.rollback(applicationId, deploymentId, principal));
    }

    @PostMapping("/applications/{applicationId}/deployments/{deploymentId}/promote")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ApiResponse<CicdDeploymentResponse> promote(
            @PathVariable Long applicationId,
            @PathVariable Long deploymentId,
            @Valid @RequestBody CicdPromotionRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(deploymentService.promote(
                applicationId, deploymentId, request.targetApplicationId(), principal));
    }

    @PostMapping("/webhooks/{applicationCode}")
    public ApiResponse<PipelineRunResponse> callback(
            @PathVariable String applicationCode,
            @RequestHeader(value = "X-DevPilot-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        return ApiResponse.success(cicdService.receive(applicationCode, signature, body));
    }
}

package com.devpilot.server.cicd.controller;

import com.devpilot.server.cicd.service.GithubObserverConfigurationService;
import com.devpilot.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cicd/applications/{applicationId}/github-observer")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class GithubObserverConfigurationController {
    private final GithubObserverConfigurationService service;
    @GetMapping public ApiResponse<GithubObserverConfigurationService.Status> status(@PathVariable Long applicationId) {
        return ApiResponse.success(service.status(applicationId));
    }
    @PutMapping public ApiResponse<GithubObserverConfigurationService.Status> save(@PathVariable Long applicationId,
            @Valid @RequestBody GithubObserverConfigurationService.Request request) {
        return ApiResponse.success(service.save(applicationId, request));
    }
    @DeleteMapping public ApiResponse<GithubObserverConfigurationService.Status> disable(@PathVariable Long applicationId,
            @RequestParam String revision) {
        return ApiResponse.success(service.disable(applicationId, revision));
    }
}

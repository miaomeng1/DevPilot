package com.devpilot.server.cicd.controller;

import com.devpilot.server.cicd.service.GithubRunCheckService;
import com.devpilot.server.common.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cicd/applications/{applicationId}/builds/{buildId}")
@RequiredArgsConstructor
public class GithubRunCheckController {
    private final GithubRunCheckService service;

    public record Request(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{10,512}", message = "查询凭据格式无效")
                          @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String repositoryToken) {
        @Override public String toString() { return "GithubRunCheckRequest[redacted]"; }
    }

    @PostMapping("/github-check")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ApiResponse<GithubRunCheckService.Result> check(@PathVariable Long applicationId,
            @PathVariable Long buildId, @Valid @RequestBody Request request) {
        return ApiResponse.success(service.check(applicationId, buildId, request.repositoryToken()));
    }
}

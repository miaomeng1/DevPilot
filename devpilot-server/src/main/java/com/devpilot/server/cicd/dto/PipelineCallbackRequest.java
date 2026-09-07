package com.devpilot.server.cicd.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PipelineCallbackRequest(
        @NotBlank @Size(max = 255) String externalRunId,
        @NotBlank @Pattern(regexp = "RUNNING|SUCCEEDED|FAILED|CANCELLED") String status,
        @NotBlank @Pattern(regexp = "PENDING|PASSED|FAILED|SKIPPED") String testStatus,
        @NotBlank @Pattern(regexp = "PENDING|PASSED|FAILED|SKIPPED") String securityStatus,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{7,64}") String commitSha,
        @NotBlank @Size(max = 255) String branchName,
        @Size(max = 1000) String imageUri,
        @Size(max = 255) String imageDigest,
        @Size(max = 1000) String runUrl,
        @Size(max = 8000) String summary,
        @Size(max = 255) String buildExternalRunId,
        @Size(max = 255) String approvalActor,
        java.time.Instant approvedAt,
        @jakarta.validation.constraints.Pattern(regexp = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") String manualApprovalId) {
}

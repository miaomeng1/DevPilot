package com.devpilot.server.cicd.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PreviewCallbackRequest(
        @NotBlank @Pattern(regexp = "DEPLOY|CLOSE") String action,
        @NotNull @Min(1) @Max(2147483647) Integer pullRequestId,
        @NotBlank @Size(max = 255) String baseBranch,
        @Size(max = 255) String externalRunId,
        @Size(max = 500) String title,
        @Size(max = 255) String branchName,
        @Pattern(regexp = "[0-9a-fA-F]{7,64}") String commitSha,
        @Pattern(regexp = "SUCCEEDED|FAILED|CANCELLED") String status,
        @Pattern(regexp = "PASSED|FAILED|SKIPPED") String testStatus,
        @Pattern(regexp = "PASSED|FAILED|SKIPPED") String securityStatus,
        @Size(max = 1000) String imageUri,
        @Size(max = 1000) String runUrl) {
}

package com.devpilot.server.cicd.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CicdConfigurationRequest(
        @NotBlank @Pattern(regexp = "GITHUB|GITLAB|WOODPECKER") String repositoryProvider,
        @NotBlank @Size(max = 1000) String repositoryUrl,
        @NotBlank @Size(max = 255) String branchName,
        @NotBlank @Pattern(regexp = "COOLIFY|DOKPLOY") String deploymentProvider,
        @Pattern(regexp = "WEBHOOK|API") String deploymentMode,
        @Size(max = 4000) String deploymentWebhookUrl,
        @Size(max = 1000) String providerBaseUrl,
        @Size(max = 4000) String providerApiToken,
        @Size(max = 255) @Pattern(regexp = "[A-Za-z0-9_-]*") String providerResourceId,
        @NotNull Boolean autoDeploy,
        @NotNull Boolean productionApproval,
        Boolean autoRollback,
        @jakarta.validation.constraints.Min(30) @jakarta.validation.constraints.Max(1800) Integer healthTimeoutSeconds,
        Boolean previewEnabled,
        @Size(max = 1000) String previewUrlTemplate,
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(720) Integer previewTtlHours,
        Boolean rotatePreviewCallbackSecret,
        @NotNull Boolean rotateCallbackSecret) {
}

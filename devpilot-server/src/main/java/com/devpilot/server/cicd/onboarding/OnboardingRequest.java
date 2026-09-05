package com.devpilot.server.cicd.onboarding;

import jakarta.validation.constraints.*;
import java.util.Map;

public record OnboardingRequest(
        @NotBlank @Pattern(regexp = "GITHUB|GITLAB") String repositoryProvider,
        @NotBlank @Size(max = 1000) String repositoryUrl,
        @NotBlank @Size(max = 4000) String repositoryToken,
        @NotBlank @Pattern(regexp = "COOLIFY|DOKPLOY") String deploymentProvider,
        @NotBlank @Size(max = 1000) String providerBaseUrl,
        @NotBlank @Size(max = 4000) String providerApiToken,
        @Size(max = 255) String projectId,
        @Size(max = 255) String environmentId,
        @Size(max = 255) String providerServerId,
        @NotBlank @Size(max = 1000) String publicBaseUrl,
        @Min(1) @Max(65535) int containerPort,
        @Min(1024) @Max(65535) int hostPort,
        @NotBlank @Pattern(regexp = "/[A-Za-z0-9/_?=&.-]*") @Size(max = 500) String healthPath,
        @NotBlank @Size(max = 500) String imageRepository,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._/-]+") @Size(max = 255) String branch,
        @Size(max = 200000) String workflowContent,
        @Size(max = 255) String registryUsername,
        @Size(max = 4000) String registryPassword,
        @Size(max = 100) Map<@Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String, @Size(max = 4000) String> environmentValues) {
    @Override public String toString() { return "OnboardingRequest[REDACTED]"; }
}

package com.devpilot.server.cicd.dto;

import java.time.LocalDateTime;

public record CicdConfigurationResponse(
        Long id,
        Long applicationId,
        String applicationCode,
        String repositoryProvider,
        String repositoryUrl,
        String branchName,
        String deploymentProvider,
        String deploymentMode,
        boolean deploymentWebhookConfigured,
        boolean providerBaseUrlConfigured,
        boolean providerApiTokenConfigured,
        String providerResourceId,
        boolean callbackSecretConfigured,
        boolean autoDeploy,
        boolean productionApproval,
        boolean autoRollback,
        int healthTimeoutSeconds,
        boolean previewEnabled,
        String previewUrlTemplate,
        int previewTtlHours,
        boolean previewCallbackSecretConfigured,
        String callbackUrl,
        String previewCallbackUrl,
        String oneTimeCallbackSecret,
        String oneTimePreviewCallbackSecret,
        LocalDateTime updatedAt) {
}

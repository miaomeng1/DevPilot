package com.devpilot.server.cicd.service;

import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.dto.CicdPreviewResponse;
import com.devpilot.server.cicd.dto.PreviewCallbackRequest;
import com.devpilot.server.cicd.entity.CicdConfigurationEntity;
import com.devpilot.server.cicd.entity.CicdPreviewEntity;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.cicd.mapper.CicdPreviewMapper;
import com.devpilot.server.cicd.service.DeploymentWebhookClient.DeploymentState;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CicdPreviewService {
    private final CicdPreviewMapper previewMapper;
    private final CicdConfigurationMapper configurationMapper;
    private final ApplicationMapper applicationMapper;
    private final DeploymentWebhookClient providerClient;
    private final SensitiveSettingCipher cipher;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public List<CicdPreviewResponse> list(Long applicationId) {
        requireApplication(applicationId);
        return previewMapper.selectByApplication(applicationId).stream().map(CicdPreviewService::toResponse).toList();
    }

    @Transactional
    public CicdPreviewResponse receive(String applicationCode, String signature, byte[] rawBody) {
        ApplicationEntity application = applicationMapper.selectByCode(applicationCode);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
        CicdConfigurationEntity configuration = requireConfiguration(application.getId());
        if (configuration.getPreviewCallbackSecretCipher() == null) {
            throw BusinessException.unauthorized("Preview 回调密钥尚未配置");
        }
        verifySignature(signature, rawBody, cipher.decrypt(configuration.getPreviewCallbackSecretCipher()));
        PreviewCallbackRequest request = parse(rawBody);
        applicationMapper.selectByIdForUpdate(application.getId());
        if (!configuration.getBranchName().equals(request.baseBranch())) {
            throw BusinessException.badRequest(40062, "Preview 目标分支与应用配置不一致");
        }
        if ("CLOSE".equals(request.action())) {
            return cleanup(configuration, request.pullRequestId(), "Pull/Merge Request 已关闭");
        }
        validateDeploy(configuration, request);
        LocalDateTime timestamp = now();
        CicdPreviewEntity preview = previewMapper.selectByPullRequest(application.getId(), request.pullRequestId());
        boolean create = preview == null;
        if (!create && Objects.equals(preview.getExternalRunId(), request.externalRunId())
                && Objects.equals(preview.getImageUri(), request.imageUri().trim())) {
            return toResponse(preview);
        }
        if (create) {
            preview = new CicdPreviewEntity();
            preview.setApplicationId(application.getId());
            preview.setPullRequestId(request.pullRequestId());
            preview.setCreatedAt(timestamp);
        }
        preview.setExternalRunId(request.externalRunId().trim());
        preview.setTitle(trimToNull(request.title()));
        preview.setBranchName(request.branchName().trim());
        preview.setCommitSha(request.commitSha().toLowerCase());
        preview.setImageUri(request.imageUri().trim());
        preview.setPreviewUrl(renderUrl(configuration.getPreviewUrlTemplate(), request.pullRequestId()));
        preview.setProvider(configuration.getDeploymentProvider());
        preview.setRunUrl(trimToNull(request.runUrl()));
        preview.setStatus("DEPLOYING");
        preview.setProviderDeploymentId(null);
        preview.setFailureReason(null);
        preview.setCompletedAt(null);
        preview.setExpiresAt(timestamp.plusHours(ttl(configuration)));
        preview.setUpdatedAt(timestamp);
        try {
            String deploymentId = providerClient.deployPreview(configuration.getDeploymentProvider(),
                    configuration.getDeploymentMode(), decrypt(configuration.getProviderBaseUrlCipher()),
                    decrypt(configuration.getProviderApiTokenCipher()), configuration.getProviderResourceId(),
                    request.pullRequestId(), request.imageUri().trim());
            if (deploymentId == null || deploymentId.isBlank()) {
                throw new IllegalStateException("Coolify 未返回 Preview deployment UUID");
            }
            preview.setProviderDeploymentId(deploymentId);
        } catch (IllegalStateException exception) {
            preview.setStatus("FAILED");
            preview.setFailureReason(safe(exception.getMessage()));
            preview.setCompletedAt(timestamp);
        }
        if (create) previewMapper.insert(preview); else previewMapper.updateById(preview);
        return toResponse(preview);
    }

    @Transactional
    public CicdPreviewResponse delete(Long applicationId, Integer pullRequestId, String reason) {
        requireApplication(applicationId);
        return cleanup(requireConfiguration(applicationId), pullRequestId, reason);
    }

    @Scheduled(fixedDelayString = "${devpilot.cicd.preview-reconcile-interval:15s}",
            initialDelayString = "${devpilot.cicd.preview-reconcile-initial-delay:30s}")
    @Transactional
    public void reconcileDeployments() {
        for (CicdPreviewEntity preview : previewMapper.selectDeploying()) {
            CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(preview.getApplicationId());
            if (configuration == null) continue;
            try {
                DeploymentState state = providerClient.fetchDeploymentState(configuration.getDeploymentProvider(),
                        decrypt(configuration.getProviderBaseUrlCipher()), decrypt(configuration.getProviderApiTokenCipher()),
                        configuration.getProviderResourceId(), preview.getProviderDeploymentId());
                if (state == DeploymentState.SUCCEEDED) {
                    preview.setStatus("READY");
                    preview.setUpdatedAt(now());
                    previewMapper.updateById(preview);
                } else if (state == DeploymentState.FAILED) {
                    preview.setStatus("FAILED");
                    preview.setFailureReason("Provider reported preview deployment failure");
                    preview.setCompletedAt(now());
                    preview.setUpdatedAt(now());
                    previewMapper.updateById(preview);
                }
            } catch (IllegalStateException ignored) {
                // Provider polling is retried; a transient control-plane outage must not destroy the preview.
            }
        }
    }

    @Scheduled(fixedDelayString = "${devpilot.cicd.preview-cleanup-interval:1m}",
            initialDelayString = "${devpilot.cicd.preview-cleanup-initial-delay:45s}")
    @Transactional
    public void cleanupExpired() {
        for (CicdPreviewEntity preview : previewMapper.selectExpired(now())) {
            CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(preview.getApplicationId());
            if (configuration == null) continue;
            cleanup(configuration, preview.getPullRequestId(), "TTL 已到期");
        }
    }

    private CicdPreviewResponse cleanup(CicdConfigurationEntity configuration, Integer pullRequestId, String reason) {
        CicdPreviewEntity preview = previewMapper.selectByPullRequest(configuration.getApplicationId(), pullRequestId);
        if (preview == null) {
            LocalDateTime timestamp = now();
            return new CicdPreviewResponse(null, configuration.getApplicationId(), pullRequestId, null, null, null,
                    null, null, null, configuration.getDeploymentProvider(), null, "DELETED", null,
                    "没有需要回收的已跟踪 Preview", timestamp, timestamp, timestamp, timestamp);
        }
        if ("DELETED".equals(preview.getStatus())) return toResponse(preview);
        LocalDateTime timestamp = now();
        try {
            providerClient.deletePreview(configuration.getDeploymentProvider(), configuration.getDeploymentMode(),
                    decrypt(configuration.getProviderBaseUrlCipher()), decrypt(configuration.getProviderApiTokenCipher()),
                    configuration.getProviderResourceId(), pullRequestId);
            preview.setStatus("DELETED");
            preview.setFailureReason(safe(reason));
            preview.setCompletedAt(timestamp);
        } catch (IllegalStateException exception) {
            preview.setStatus("CLEANUP_FAILED");
            preview.setFailureReason(safe(exception.getMessage()));
            preview.setExpiresAt(timestamp.plusMinutes(15));
        }
        preview.setUpdatedAt(timestamp);
        previewMapper.updateById(preview);
        return toResponse(preview);
    }

    private PreviewCallbackRequest parse(byte[] rawBody) {
        PreviewCallbackRequest request;
        try {
            request = objectMapper.readValue(rawBody, PreviewCallbackRequest.class);
        } catch (Exception exception) {
            throw BusinessException.badRequest(40061, "Preview 回调 JSON 无效");
        }
        List<String> violations = validator.validate(request).stream().map(ConstraintViolation::getMessage).toList();
        if (!violations.isEmpty()) {
            throw BusinessException.badRequest(40061, "Preview 回调字段无效: " + violations.getFirst());
        }
        return request;
    }

    private static void validateDeploy(CicdConfigurationEntity configuration, PreviewCallbackRequest request) {
        if (!Integer.valueOf(1).equals(configuration.getPreviewEnabled())) {
            throw BusinessException.badRequest(40063, "此应用尚未启用 Preview 环境");
        }
        if (!"COOLIFY".equals(configuration.getDeploymentProvider())
                || !"API".equals(configuration.getDeploymentMode())) {
            throw BusinessException.badRequest(40064, "托管 Preview 当前需要 Coolify API 模式");
        }
        if (blank(request.externalRunId()) || blank(request.branchName()) || blank(request.commitSha())
                || blank(request.status()) || blank(request.testStatus()) || blank(request.securityStatus())
                || blank(request.imageUri())) {
            throw BusinessException.badRequest(40065, "部署 Preview 缺少流水线、分支、提交、门禁或镜像信息");
        }
        if (!"SUCCEEDED".equals(request.status()) || !"PASSED".equals(request.testStatus())
                || !"PASSED".equals(request.securityStatus())) {
            throw BusinessException.badRequest(40066, "Preview 必须通过测试与安全扫描");
        }
        validateOptionalHttpUrl(request.runUrl());
        String image = request.imageUri().trim();
        if (!(image.matches(".+@sha256:[0-9a-fA-F]{64}$") || image.matches(".+:sha-[0-9a-fA-F]{7,64}$"))) {
            throw BusinessException.badRequest(40067, "Preview 必须使用不可变 digest 或 sha-* 镜像");
        }
        int tagMarker = image.lastIndexOf(":sha-");
        if (tagMarker > image.lastIndexOf('/') && !request.commitSha().toLowerCase()
                .startsWith(image.substring(tagMarker + 5).toLowerCase())) {
            throw BusinessException.badRequest(40068, "Preview 镜像标签必须与提交 SHA 一致");
        }
    }

    public static void validateTemplate(String template) {
        if (blank(template) || !template.contains("{{pr_id}}")) {
            throw BusinessException.badRequest(40069, "Preview URL 模板必须包含 {{pr_id}}");
        }
        String rendered = renderUrl(template, 123);
        try {
            URI uri = new URI(rendered);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new URISyntaxException(rendered, "invalid preview URL");
            }
        } catch (URISyntaxException exception) {
            throw BusinessException.badRequest(40069, "Preview URL 模板必须生成有效的 HTTP(S) 地址");
        }
    }

    private static void validateOptionalHttpUrl(String value) {
        if (blank(value)) return;
        try {
            URI uri = new URI(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new URISyntaxException(value, "invalid URL");
            }
        } catch (URISyntaxException exception) {
            throw BusinessException.badRequest(40070, "Preview CI 运行地址必须是有效的 HTTP(S) URL");
        }
    }

    private static String renderUrl(String template, int pullRequestId) {
        return template.trim().replace("{{pr_id}}", Integer.toString(pullRequestId));
    }

    private ApplicationEntity requireApplication(Long applicationId) {
        ApplicationEntity application = applicationMapper.selectById(applicationId);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
        return application;
    }

    private CicdConfigurationEntity requireConfiguration(Long applicationId) {
        CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(applicationId);
        if (configuration == null) throw BusinessException.notFound(40440, "CI/CD 配置不存在");
        return configuration;
    }

    private String decrypt(String value) {
        return value == null ? null : cipher.decrypt(value);
    }

    private static void verifySignature(String signature, byte[] body, String secret) {
        try {
            if (signature == null || !signature.startsWith("sha256=")) {
                throw BusinessException.unauthorized("Preview 回调签名缺失");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] supplied = HexFormat.of().parseHex(signature.substring(7));
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw BusinessException.unauthorized("Preview 回调签名无效");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unauthorized("Preview 回调签名无效");
        }
    }

    private static CicdPreviewResponse toResponse(CicdPreviewEntity entity) {
        return new CicdPreviewResponse(entity.getId(), entity.getApplicationId(), entity.getPullRequestId(),
                entity.getExternalRunId(), entity.getTitle(), entity.getBranchName(), entity.getCommitSha(), entity.getImageUri(),
                entity.getPreviewUrl(), entity.getProvider(), entity.getProviderDeploymentId(), entity.getStatus(),
                entity.getRunUrl(), entity.getFailureReason(), entity.getExpiresAt(), entity.getCreatedAt(),
                entity.getUpdatedAt(), entity.getCompletedAt());
    }

    private static int ttl(CicdConfigurationEntity configuration) {
        return configuration.getPreviewTtlHours() == null ? 72 : configuration.getPreviewTtlHours();
    }

    private static String safe(String value) {
        String text = blank(value) ? "Preview provider request failed" : value;
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

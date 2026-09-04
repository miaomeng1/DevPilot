package com.devpilot.server.cicd.service;

import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.dto.CicdConfigurationRequest;
import com.devpilot.server.cicd.dto.CicdConfigurationResponse;
import com.devpilot.server.cicd.dto.PipelineCallbackRequest;
import com.devpilot.server.cicd.dto.PipelineRunResponse;
import com.devpilot.server.cicd.entity.CicdConfigurationEntity;
import com.devpilot.server.cicd.entity.CicdPipelineRunEntity;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.cicd.mapper.CicdPipelineRunMapper;
import com.devpilot.server.cicd.mapper.CicdPreviewMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CicdService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CicdConfigurationMapper configurationMapper;
    private final CicdPipelineRunMapper pipelineMapper;
    private final CicdPreviewMapper previewMapper;
    private final ApplicationMapper applicationMapper;
    private final SensitiveSettingCipher cipher;
    private final CicdDeploymentService deploymentService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public CicdConfigurationResponse getConfiguration(Long applicationId) {
        ApplicationEntity application = requireApplication(applicationId);
        CicdConfigurationEntity entity = configurationMapper.selectByApplicationId(applicationId);
        if (entity == null) {
            throw BusinessException.notFound(40440, "CI/CD 配置不存在");
        }
        return toConfiguration(entity, application, null, null);
    }

    @Transactional
    public CicdConfigurationResponse saveConfiguration(Long applicationId, CicdConfigurationRequest request,
                                                       DevPilotPrincipal principal) {
        ApplicationEntity application = requireApplication(applicationId);
        applicationMapper.selectByIdForUpdate(applicationId);
        validateHttpUrl(request.repositoryUrl(), "仓库 URL");
        CicdConfigurationEntity entity = configurationMapper.selectByApplicationId(applicationId);
        boolean create = entity == null;
        if (create) {
            entity = new CicdConfigurationEntity();
            entity.setApplicationId(applicationId);
            entity.setCreatedBy(principal.userId());
            entity.setCreatedAt(now());
        }
        String mode = request.deploymentMode() == null ? "WEBHOOK" : request.deploymentMode();
        String webhook = trimToNull(request.deploymentWebhookUrl());
        if (webhook != null) {
            validateHttpUrl(webhook, "部署 Webhook URL");
            entity.setDeploymentWebhookCipher(cipher.encrypt(webhook));
        } else if ("WEBHOOK".equals(mode) && (create || entity.getDeploymentWebhookCipher() == null)) {
            throw BusinessException.badRequest(40040, "首次配置必须提供部署 Webhook URL");
        }
        String baseUrl = trimToNull(request.providerBaseUrl());
        String apiToken = trimToNull(request.providerApiToken());
        String resourceId = trimToNull(request.providerResourceId());
        if (!create && previewMapper.countActive(applicationId) > 0) {
            boolean providerChanged = !entity.getDeploymentProvider().equals(request.deploymentProvider())
                    || !valueOr(entity.getDeploymentMode(), "WEBHOOK").equals(mode)
                    || !entity.getRepositoryProvider().equals(request.repositoryProvider())
                    || !entity.getRepositoryUrl().equals(normalizeUrl(request.repositoryUrl()))
                    || !entity.getBranchName().equals(request.branchName().trim())
                    || (resourceId != null && !resourceId.equals(entity.getProviderResourceId()))
                    || (baseUrl != null && !normalizeUrl(baseUrl).equals(decrypt(entity.getProviderBaseUrlCipher())))
                    || apiToken != null
                    || Boolean.TRUE.equals(request.rotatePreviewCallbackSecret());
            if (providerChanged) {
                throw BusinessException.conflict(40955,
                        "仍有活动 Preview；请先回收后再更换仓库、分支、Provider 凭据、资源 ID 或 Preview 密钥");
            }
        }
        if (baseUrl != null) {
            validateHttpUrl(baseUrl, "部署平台地址");
            entity.setProviderBaseUrlCipher(cipher.encrypt(normalizeUrl(baseUrl)));
        }
        if (apiToken != null) entity.setProviderApiTokenCipher(cipher.encrypt(apiToken));
        if (resourceId != null) entity.setProviderResourceId(resourceId);
        if ("API".equals(mode) && ((create && (baseUrl == null || apiToken == null || resourceId == null))
                || entity.getProviderBaseUrlCipher() == null || entity.getProviderApiTokenCipher() == null
                || entity.getProviderResourceId() == null)) {
            throw BusinessException.badRequest(40046, "API 模式必须配置平台地址、最小权限 API Token 和资源 ID");
        }
        boolean previewEnabled = Boolean.TRUE.equals(request.previewEnabled());
        String previewUrlTemplate = trimToNull(request.previewUrlTemplate());
        if (previewEnabled) {
            if (!"COOLIFY".equals(request.deploymentProvider()) || !"API".equals(mode)) {
                throw BusinessException.badRequest(40064, "托管 Preview 当前需要 Coolify API 模式");
            }
            if (!("GITHUB".equals(request.repositoryProvider()) || "GITLAB".equals(request.repositoryProvider()))) {
                throw BusinessException.badRequest(40064, "自动 Preview Workflow 当前支持 GitHub 与 GitLab");
            }
            CicdPreviewService.validateTemplate(previewUrlTemplate);
        }
        String oneTimeSecret = null;
        if (create || request.rotateCallbackSecret()) {
            oneTimeSecret = newSecret();
            entity.setCallbackSecretCipher(cipher.encrypt(oneTimeSecret));
        }
        String oneTimePreviewSecret = null;
        if (previewEnabled && (entity.getPreviewCallbackSecretCipher() == null
                || Boolean.TRUE.equals(request.rotatePreviewCallbackSecret()))) {
            oneTimePreviewSecret = newPreviewSecret();
            entity.setPreviewCallbackSecretCipher(cipher.encrypt(oneTimePreviewSecret));
        }
        entity.setRepositoryProvider(request.repositoryProvider());
        entity.setRepositoryUrl(normalizeUrl(request.repositoryUrl()));
        entity.setBranchName(request.branchName().trim());
        entity.setDeploymentProvider(request.deploymentProvider());
        entity.setDeploymentMode(mode);
        entity.setAutoDeploy(request.autoDeploy() ? 1 : 0);
        entity.setProductionApproval(request.productionApproval() ? 1 : 0);
        entity.setAutoRollback(Boolean.FALSE.equals(request.autoRollback()) ? 0 : 1);
        entity.setHealthTimeoutSeconds(request.healthTimeoutSeconds() == null ? 120 : request.healthTimeoutSeconds());
        entity.setPreviewEnabled(previewEnabled ? 1 : 0);
        entity.setPreviewUrlTemplate(previewUrlTemplate);
        entity.setPreviewTtlHours(request.previewTtlHours() == null ? 72 : request.previewTtlHours());
        entity.setUpdatedAt(now());
        if (create) configurationMapper.insert(entity); else configurationMapper.updateById(entity);
        return toConfiguration(entity, application, oneTimeSecret, oneTimePreviewSecret);
    }

    public List<PipelineRunResponse> listRuns(Long applicationId) {
        requireApplication(applicationId);
        return pipelineMapper.selectRecent(applicationId, 100).stream().map(CicdService::toRun).toList();
    }

    @Transactional
    public PipelineRunResponse receive(String applicationCode, String signature, byte[] rawBody) {
        ApplicationEntity application = applicationMapper.selectByCode(applicationCode);
        if (application == null) {
            throw BusinessException.notFound(40420, "应用不存在");
        }
        CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(application.getId());
        if (configuration == null) {
            throw BusinessException.notFound(40440, "CI/CD 配置不存在");
        }
        verifySignature(signature, rawBody, cipher.decrypt(configuration.getCallbackSecretCipher()));
        PipelineCallbackRequest request;
        try {
            request = objectMapper.readValue(rawBody, PipelineCallbackRequest.class);
        } catch (Exception exception) {
            throw BusinessException.badRequest(40041, "流水线回调 JSON 无效");
        }
        List<String> violations = validator.validate(request).stream().map(ConstraintViolation::getMessage).toList();
        if (!violations.isEmpty()) {
            throw BusinessException.badRequest(40041, "流水线回调字段无效: " + violations.getFirst());
        }
        if (!configuration.getBranchName().equals(request.branchName())) {
            throw BusinessException.badRequest(40042, "回调分支与应用配置不一致");
        }
        validateSuccessfulGate(request);
        CicdPipelineRunEntity run = pipelineMapper.selectByExternalRunId(application.getId(), request.externalRunId());
        boolean create = run == null;
        LocalDateTime timestamp = now();
        if (create) {
            run = new CicdPipelineRunEntity();
            run.setApplicationId(application.getId());
            run.setExternalRunId(request.externalRunId());
            run.setStartedAt(timestamp);
            run.setDeployStatus("NOT_STARTED");
        }
        boolean firstSuccessfulEvent = !"SUCCEEDED".equals(run.getStatus()) && "SUCCEEDED".equals(request.status());
        run.setCommitSha(request.commitSha().toLowerCase());
        run.setBranchName(request.branchName());
        run.setStatus(request.status());
        run.setTestStatus(request.testStatus());
        run.setSecurityStatus(request.securityStatus());
        run.setImageUri(trimToNull(request.imageUri()));
        run.setImageDigest(trimToNull(request.imageDigest()));
        run.setRunUrl(trimToNull(request.runUrl()));
        run.setSummary(trimToNull(request.summary()));
        run.setCompletedAt(terminal(request.status()) ? timestamp : null);
        run.setUpdatedAt(timestamp);
        if (create) pipelineMapper.insert(run); else pipelineMapper.updateById(run);
        if (firstSuccessfulEvent && configuration.getAutoDeploy() == 1) {
            deploymentService.requestRelease(configuration, run);
        }
        return toRun(run);
    }

    private static void validateSuccessfulGate(PipelineCallbackRequest request) {
        if (!"SUCCEEDED".equals(request.status())) return;
        if (!"PASSED".equals(request.testStatus()) || !"PASSED".equals(request.securityStatus())) {
            throw BusinessException.badRequest(40043, "测试与安全扫描必须全部通过后才能标记流水线成功");
        }
        String image = trimToNull(request.imageUri());
        if (image == null || !(image.matches(".+@sha256:[0-9a-fA-F]{64}$")
                || image.matches(".+:sha-[0-9a-fA-F]{7,64}$"))) {
            throw BusinessException.badRequest(40044, "成功流水线必须提供不可变 digest 或 sha-* 镜像");
        }
        int tagMarker = image.lastIndexOf(":sha-");
        if (tagMarker > image.lastIndexOf('/') && !request.commitSha().toLowerCase()
                .startsWith(image.substring(tagMarker + 5).toLowerCase())) {
            throw BusinessException.badRequest(40048, "镜像 sha-* 标签必须与流水线提交 SHA 一致");
        }
    }

    private static void verifySignature(String signature, byte[] body, String secret) {
        try {
            if (signature == null || !signature.startsWith("sha256=")) {
                throw BusinessException.unauthorized("流水线回调签名缺失");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] supplied = HexFormat.of().parseHex(signature.substring(7));
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw BusinessException.unauthorized("流水线回调签名无效");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unauthorized("流水线回调签名无效");
        }
    }

    private CicdConfigurationResponse toConfiguration(CicdConfigurationEntity entity, ApplicationEntity application,
                                                       String oneTimeSecret, String oneTimePreviewSecret) {
        return new CicdConfigurationResponse(entity.getId(), entity.getApplicationId(), application.getCode(),
                entity.getRepositoryProvider(), entity.getRepositoryUrl(), entity.getBranchName(),
                entity.getDeploymentProvider(), valueOr(entity.getDeploymentMode(), "WEBHOOK"),
                entity.getDeploymentWebhookCipher() != null, entity.getProviderBaseUrlCipher() != null,
                entity.getProviderApiTokenCipher() != null, entity.getProviderResourceId(),
                entity.getCallbackSecretCipher() != null, entity.getAutoDeploy() == 1,
                entity.getProductionApproval() == 1, entity.getAutoRollback() == null || entity.getAutoRollback() == 1,
                entity.getHealthTimeoutSeconds() == null ? 120 : entity.getHealthTimeoutSeconds(),
                Integer.valueOf(1).equals(entity.getPreviewEnabled()), entity.getPreviewUrlTemplate(),
                entity.getPreviewTtlHours() == null ? 72 : entity.getPreviewTtlHours(),
                entity.getPreviewCallbackSecretCipher() != null,
                "/api/cicd/webhooks/" + application.getCode(),
                "/api/cicd/webhooks/" + application.getCode() + "/previews",
                oneTimeSecret, oneTimePreviewSecret, entity.getUpdatedAt());
    }

    private static PipelineRunResponse toRun(CicdPipelineRunEntity run) {
        return new PipelineRunResponse(run.getId(), run.getApplicationId(), run.getExternalRunId(),
                run.getCommitSha(), run.getBranchName(), run.getStatus(), run.getTestStatus(),
                run.getSecurityStatus(), run.getImageUri(), run.getImageDigest(), run.getRunUrl(),
                run.getSummary(), run.getDeployStatus(), run.getDeployError(), run.getStartedAt(),
                run.getCompletedAt(), run.getUpdatedAt());
    }

    private ApplicationEntity requireApplication(Long applicationId) {
        ApplicationEntity application = applicationMapper.selectById(applicationId);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
        return application;
    }

    private static void validateHttpUrl(String value, String label) {
        try {
            URI uri = new URI(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new URISyntaxException(value, "invalid HTTP URL");
            }
        } catch (URISyntaxException exception) {
            throw BusinessException.badRequest(40045, label + " 必须是有效且不包含用户凭据的 HTTP(S) 地址");
        }
    }

    private static String normalizeUrl(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "dp_ci_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String newPreviewSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "dp_preview_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean terminal(String status) {
        return !"RUNNING".equals(status);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maximum) {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String decrypt(String value) {
        return value == null ? null : cipher.decrypt(value);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

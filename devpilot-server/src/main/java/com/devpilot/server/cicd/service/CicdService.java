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
    private final com.devpilot.server.automation.service.AutomationWebhookService automationWebhooks;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    @org.springframework.beans.factory.annotation.Value("${devpilot.cicd.running-stale-after:2h}")
    private java.time.Duration runningStaleAfter;

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
        entity.setProductionApproval(1); // Production confirmation lives in the generated CI workflow, not a bypass switch.
        if (create || request.autoRollback() != null) {
            entity.setAutoRollback(Boolean.TRUE.equals(request.autoRollback()) ? 1 : 0);
        }
        entity.setHealthTimeoutSeconds(request.healthTimeoutSeconds() == null ? 120 : request.healthTimeoutSeconds());
        entity.setPreviewEnabled(previewEnabled ? 1 : 0);
        entity.setPreviewUrlTemplate(previewUrlTemplate);
        entity.setPreviewTtlHours(request.previewTtlHours() == null ? 72 : request.previewTtlHours());
        entity.setUpdatedAt(now());
        if (create) configurationMapper.insert(entity); else configurationMapper.updateById(entity);
        if (baseUrl != null || apiToken != null || resourceId != null) {
            configurationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<CicdConfigurationEntity>()
                    .eq("id", entity.getId()).set("provider_verified_at", null).set("provider_verification_error", null));
        }
        if (create || Boolean.TRUE.equals(request.rotateCallbackSecret())) {
            configurationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<CicdConfigurationEntity>()
                    .eq("id", entity.getId()).set("callback_verified_at", null).set("build_callback_verified_at", null));
        }
        return toConfiguration(entity, application, oneTimeSecret, oneTimePreviewSecret);
    }

    public List<PipelineRunResponse> listRuns(Long applicationId) {
        requireApplication(applicationId);
        return pipelineMapper.selectRecent(applicationId, 100).stream().map(this::toRun).toList();
    }

    @Transactional
    public PipelineRunResponse receive(String applicationCode, String signature, byte[] rawBody) {
        return receiveEvent(applicationCode, signature, rawBody, false);
    }

    @Transactional
    public PipelineRunResponse receiveBuild(String applicationCode, String signature, byte[] rawBody) {
        return receiveEvent(applicationCode, signature, rawBody, true);
    }

    private PipelineRunResponse receiveEvent(String applicationCode, String signature, byte[] rawBody, boolean buildOnly) {
        ApplicationEntity application = applicationMapper.selectByCode(applicationCode);
        if (application == null) {
            throw BusinessException.notFound(40420, "应用不存在");
        }
        application = applicationMapper.selectByIdForUpdate(application.getId());
        CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(application.getId());
        if (configuration == null) {
            throw BusinessException.notFound(40440, "CI/CD 配置不存在");
        }
        String releaseKey = cipher.decrypt(configuration.getCallbackSecretCipher());
        verifySignature(signature, rawBody, buildOnly ? BuildStatusKey.derive(releaseKey) : releaseKey);
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
        if (trimToNull(request.runUrl()) != null) validateHttpUrl(request.runUrl(), "CI 任务链接");
        if (!configuration.getBranchName().equals(request.branchName())) {
            throw BusinessException.badRequest(40042, "回调分支与应用配置不一致");
        }
        if (buildOnly != request.externalRunId().startsWith("build:")) {
            throw BusinessException.badRequest(40041, "构建状态与发布回调必须使用独立的 Run ID 命名空间");
        }
        validateSuccessfulGate(request);
        if (buildOnly && "SUCCEEDED".equals(request.status()) && (request.imageUri() == null
                || !request.imageUri().matches(".+@sha256:[a-f0-9]{64}$"))) {
            throw BusinessException.badRequest(40050, "待发布构建必须提供完整镜像 digest，不能使用 tag");
        }
        if (buildOnly && (request.buildExternalRunId() != null || request.approvalActor() != null || request.approvedAt() != null || request.manualApprovalId() != null)) {
            throw BusinessException.badRequest(40041, "构建状态上报不能声明发布确认");
        }
        if (!buildOnly && request.buildExternalRunId() != null) {
            var source = pipelineMapper.selectByExternalRunId(application.getId(), request.buildExternalRunId());
            if (!request.buildExternalRunId().startsWith("build:") || source == null || !"SUCCEEDED".equals(source.getStatus())
                    || !source.getCommitSha().equalsIgnoreCase(request.commitSha())
                    || !java.util.Objects.equals(source.getImageUri(), request.imageUri())) {
                throw BusinessException.badRequest(40050, "发布必须引用本应用已通过的构建，提交与镜像 digest 必须一致");
            }
            if (request.approvalActor() == null || request.approvalActor().isBlank() || request.approvedAt() == null
                    || request.approvedAt().isAfter(java.time.Instant.now().plusSeconds(300))) {
                throw BusinessException.badRequest(40050, "关联构建的发布必须提供有效的 CI 发起人和任务开始时间；该记录不等同于人工审批证据");
            }
        } else if (!buildOnly && (request.approvalActor() != null || request.approvedAt() != null)) {
            throw BusinessException.badRequest(40050, "CI 发布发起信息必须关联构建记录");
        }
        configurationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<CicdConfigurationEntity>()
                .eq("id", configuration.getId()).eq("callback_secret_cipher", configuration.getCallbackSecretCipher())
                .set(buildOnly ? "build_callback_verified_at" : "callback_verified_at", now()));
        CicdPipelineRunEntity run = pipelineMapper.selectByExternalRunId(application.getId(), request.externalRunId());
        boolean create = run == null;
        if (!buildOnly && run != null && run.getBuildExternalRunId() != null && terminal(run.getStatus())) {
            if (!java.util.Objects.equals(run.getBuildExternalRunId(), request.buildExternalRunId())
                    || !java.util.Objects.equals(run.getImageUri(), request.imageUri())
                    || !java.util.Objects.equals(run.getApprovalActor(), request.approvalActor())
                    || !run.getCommitSha().equalsIgnoreCase(request.commitSha())) {
                throw BusinessException.conflict(40941, "已完成发布的来源及 CI 发起人不能修改");
            }
            if (!"AWAITING_APPROVAL".equals(run.getDeployStatus()) || !"SUCCEEDED".equals(request.status())) {
                if (!java.util.Objects.equals(run.getManualApprovalId(), request.manualApprovalId())) throw BusinessException.conflict(40941, "已提交发布的人工确认不能更换");
                return toRun(run);
            }
        }
        if (buildOnly && run != null) {
            if (!run.getCommitSha().equalsIgnoreCase(request.commitSha())) throw BusinessException.conflict(40941, "同一构建不能改变提交");
            if (terminal(run.getStatus())) {
                // A workflow can fail only because its callback delivery failed.
                // A signed final build report may supersede a read-only observation,
                // but cannot overwrite a previous signed final result or regress to RUNNING.
                boolean observedFailure = "GITHUB_OBSERVATION".equals(run.getBuildResultSource())
                        && ("FAILED".equals(run.getStatus()) || "CANCELLED".equals(run.getStatus()));
                if (!observedFailure || !terminal(request.status())) return toRun(run);
            }
        }
        LocalDateTime timestamp = now();
        if (create) {
            run = new CicdPipelineRunEntity();
            run.setApplicationId(application.getId());
            run.setExternalRunId(request.externalRunId());
            run.setStartedAt(timestamp);
            run.setDeployStatus("NOT_STARTED");
        }
        boolean firstSuccessfulEvent = !"SUCCEEDED".equals(run.getStatus()) && "SUCCEEDED".equals(request.status());
        boolean resumeApproval = "AWAITING_APPROVAL".equals(run.getDeployStatus()) && "SUCCEEDED".equals(request.status());
        run.setCommitSha(request.commitSha().toLowerCase());
        run.setBranchName(request.branchName());
        run.setStatus(request.status());
        run.setBuildResultSource("CALLBACK");
        if (buildOnly) run.setDeployStatus("SUCCEEDED".equals(request.status()) ? "AWAITING_APPROVAL"
                : "RUNNING".equals(request.status()) ? "BUILDING" : "BUILD_FAILED");
        run.setTestStatus(request.testStatus());
        run.setSecurityStatus(request.securityStatus());
        run.setImageUri(trimToNull(request.imageUri()));
        String imageDigest = trimToNull(request.imageDigest());
        if (imageDigest == null && run.getImageUri() != null && run.getImageUri().contains("@sha256:")) {
            imageDigest = run.getImageUri().substring(run.getImageUri().lastIndexOf('@') + 1);
        }
        run.setImageDigest(imageDigest);
        run.setRunUrl(trimToNull(request.runUrl()));
        run.setSummary(trimToNull(request.summary()));
        run.setBuildExternalRunId(request.buildExternalRunId());
        run.setApprovalActor(request.approvalActor());
        run.setManualApprovalId(request.manualApprovalId());
        run.setApprovedAt(request.approvedAt() == null ? null : LocalDateTime.ofInstant(request.approvedAt(), ZoneOffset.UTC));
        run.setCompletedAt(terminal(request.status()) ? timestamp : null);
        run.setUpdatedAt(timestamp);
        if (create) pipelineMapper.insert(run); else pipelineMapper.updateById(run);
        if (buildOnly && terminal(run.getStatus()) && !"SUCCEEDED".equals(run.getStatus())) {
            automationWebhooks.publishBuildFailure(run, application);
        }
        if (!buildOnly && (firstSuccessfulEvent || resumeApproval) && configuration.getAutoDeploy() == 1) {
            deploymentService.requestRelease(configuration, run);
        }
        return toRun(run);
    }

    static void validateSuccessfulGate(PipelineCallbackRequest request) {
        if (!"SUCCEEDED".equals(request.status())) return;
        if (!"PASSED".equals(request.testStatus()) || !"PASSED".equals(request.securityStatus())) {
            throw BusinessException.badRequest(40043, "测试与安全扫描必须全部通过后才能标记流水线成功");
        }
        String image = trimToNull(request.imageUri());
        if (image == null || !(image.matches(".+@sha256:[0-9a-fA-F]{64}$")
                || image.matches(".+:sha-[0-9a-fA-F]{7,64}$"))) {
            throw BusinessException.badRequest(40044, "成功流水线必须提供不可变 digest 或 sha-* 镜像");
        }
        String digest = trimToNull(request.imageDigest());
        if (digest != null && (!digest.matches("sha256:[0-9a-f]{64}")
                || !image.endsWith("@" + digest))) {
            throw BusinessException.badRequest(40049, "镜像摘要必须与 imageUri 中的 digest 完全一致");
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
                entity.getProductionApproval() == 1, Integer.valueOf(1).equals(entity.getAutoRollback()),
                entity.getHealthTimeoutSeconds() == null ? 120 : entity.getHealthTimeoutSeconds(),
                Integer.valueOf(1).equals(entity.getPreviewEnabled()), entity.getPreviewUrlTemplate(),
                entity.getPreviewTtlHours() == null ? 72 : entity.getPreviewTtlHours(),
                entity.getPreviewCallbackSecretCipher() != null,
                "/api/cicd/webhooks/" + application.getCode(),
                "/api/cicd/webhooks/" + application.getCode() + "/previews",
                oneTimeSecret, oneTimePreviewSecret, oneTimeSecret == null ? null : BuildStatusKey.derive(oneTimeSecret), entity.getUpdatedAt());
    }

    private PipelineRunResponse toRun(CicdPipelineRunEntity run) {
        boolean githubNewer = run.getGithubCheckedAt() != null && (run.getUpdatedAt() == null
                || !run.getGithubCheckedAt().isBefore(run.getUpdatedAt()));
        boolean githubActive = githubNewer && "ACTIVE".equals(run.getGithubObservation())
                && !run.getGithubCheckedAt().isAfter(now())
                && run.getGithubCheckedAt().plusMinutes(2).isAfter(now());
        boolean stale = "RUNNING".equals(run.getStatus()) && (run.getUpdatedAt() == null
                || !run.getUpdatedAt().plus(runningStaleAfter).isAfter(now()));
        if (githubActive) stale = false;
        if (githubNewer && !githubActive && "RUNNING".equals(run.getStatus())) stale = true;
        String observationMessage = stale ? "长时间未收到 CI 终态，当前结果未知。请打开构建任务核对是否仍在执行、已取消或回调失败；修复回调后重新上报。此提示不会触发部署、取消任务或判定构建失败。" : null;
        if (githubNewer) observationMessage = "GitHub 核对 · " + run.getGithubCheckedAt() + " UTC："
                + ("ACTIVE".equals(run.getGithubObservation()) && !githubActive
                    ? "上次记录为排队或运行，但该观察已过期或时间异常。请重新核对任务详情；不能据此判断任务仍在运行或应用在线。"
                    : GithubObservationGuidance.describe(run.getGithubObservation()));
        return new PipelineRunResponse(run.getId(), run.getApplicationId(), run.getExternalRunId(),
                run.getCommitSha(), run.getBranchName(), run.getStatus(), run.getTestStatus(),
                run.getSecurityStatus(), run.getImageUri(), run.getImageDigest(), run.getRunUrl(),
                run.getSummary(), run.getDeployStatus(), run.getDeployError(), run.getStartedAt(),
                run.getCompletedAt(), run.getUpdatedAt(), run.getBuildExternalRunId(), run.getApprovalActor(), run.getApprovedAt(), run.getManualApprovalId(),
                stale ? "STALE" : terminal(run.getStatus()) ? "TERMINAL_REPORTED" : "LAST_REPORTED",
                observationMessage);
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

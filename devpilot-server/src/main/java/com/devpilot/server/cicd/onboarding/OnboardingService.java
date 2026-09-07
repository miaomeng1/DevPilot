package com.devpilot.server.cicd.onboarding;

import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.dto.CicdConfigurationRequest;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.cicd.service.CicdService;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OnboardingService {
    private final OnboardingMapper jobs;
    private final ApplicationMapper applications;
    private final CicdConfigurationMapper configurations;
    private final CicdService cicd;
    private final RepositoryOnboardingClient repositories;
    private final ProviderOnboardingClient providers;
    private final HostPortPreflight hostPorts;
    private final SensitiveSettingCipher cipher;
    private final ObjectMapper json;

    public record Status(String id, Long applicationId, int stage, String status, String resourceId,
                         String changeUrl, String errorMessage, LocalDateTime updatedAt,
                         Integer containerPort, Integer hostPort, String healthPath,
                         java.util.Set<String> environmentVariableNames, boolean registryCredentialsRequired) { }

    @Transactional
    public Status start(Long applicationId, OnboardingRequest request) {
        var app = applications.selectByIdForUpdate(applicationId);
        if (app == null) throw BusinessException.notFound(40420, "应用不存在");
        var existing = jobs.byApplication(applicationId);
        if (existing != null) return status(existing);
        if (configurations.selectByApplicationId(applicationId) != null) {
            throw BusinessException.conflict(40970, "应用已有 CI/CD 配置；自动接入不会覆盖已有部署目标，请使用新应用");
        }
        validate(request);
        var job = new OnboardingJob();
        job.setId(UUID.randomUUID().toString()); job.setApplicationId(applicationId);
        job.setRequestCipher(encrypt(request)); job.setStage(0); job.setStatus("PENDING");
        job.setCredentialsUpdatedAt(now());
        job.setCreatedAt(now()); job.setUpdatedAt(now()); jobs.insert(job);
        return status(job);
    }

    public Status get(Long applicationId) { return status(require(applicationId)); }

    @Transactional
    public Status updatePorts(Long applicationId, int containerPort, int hostPort, String healthPath) {
        var job = jobs.lockApplication(applicationId);
        if (job == null) throw BusinessException.notFound(40470, "没有接入任务");
        if (job.getStage() != 0 || job.getResourceId() != null || job.getRequestCipher() == null || "EXPIRED".equals(job.getStatus())) {
            throw BusinessException.conflict(40970, "只能修改尚未创建远端资源的预检任务；已有资源的端口需单独确认变更");
        }
        if (job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now())) {
            throw BusinessException.conflict(40970, "预检仍在执行，请等待结束后修改参数");
        }
        try {
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(cipher.decrypt(job.getRequestCipher()));
            payload.put("containerPort", containerPort); payload.put("hostPort", hostPort); payload.put("healthPath", healthPath);
            jobs.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<OnboardingJob>()
                    .eq("id", job.getId()).set("request_cipher", cipher.encrypt(payload.toString()))
                    .set("status", "PENDING").set("error_message", null).set("updated_at", now()));
            return get(applicationId);
        } catch (Exception failure) { throw BusinessException.badRequest(40070, "无法更新接入参数"); }
    }

    @Transactional
    public Status credentials(Long applicationId, String repositoryToken, String providerApiToken, String registryPassword,
                              java.util.Map<String, String> environmentValues, Boolean providerQuotaConfirmed) {
        var job = jobs.lockApplication(applicationId);
        if (job == null) throw BusinessException.notFound(40470, "没有接入任务");
        if (job.getRequestCipher() == null) throw BusinessException.conflict(40970, "任务已完成或凭据已清除；不能修改此任务授权");
        if (job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now())) {
            throw BusinessException.conflict(40970, "当前步骤仍在执行，请等待完成后再更换凭据");
        }
        if ((repositoryToken == null || repositoryToken.isBlank()) && (providerApiToken == null || providerApiToken.isBlank())
                && (registryPassword == null || registryPassword.isBlank())) {
            throw BusinessException.badRequest(40070, "请至少填写一项新凭据；空请求不会延长凭据保留时间");
        }
        try {
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(cipher.decrypt(job.getRequestCipher()));
            if ("EXPIRED".equals(job.getStatus())) {
                if (repositoryToken == null || repositoryToken.isBlank() || providerApiToken == null || providerApiToken.isBlank()) {
                    throw BusinessException.badRequest(40070, "过期任务需要重新提供仓库 Token 和部署平台 Key");
                }
                if (!payload.path("registryUsername").asText("").isBlank() && (registryPassword == null || registryPassword.isBlank())) {
                    throw BusinessException.badRequest(40070, "请重新提供私有 Registry 密码");
                }
                if (job.getStage() <= 2 && payload.path("environmentValues").isObject()) {
                    var names = new java.util.HashSet<String>();
                    payload.path("environmentValues").fieldNames().forEachRemaining(names::add);
                    if (environmentValues == null || !environmentValues.keySet().equals(names)) {
                        throw BusinessException.badRequest(40070, "请重新提供原计划的全部环境变量，变量名不能改变");
                    }
                    payload.set("environmentValues", json.valueToTree(environmentValues));
                }
                payload.put("providerQuotaConfirmed", Boolean.TRUE.equals(providerQuotaConfirmed));
            }
            if (repositoryToken != null && !repositoryToken.isBlank()) payload.put("repositoryToken", repositoryToken);
            if (providerApiToken != null && !providerApiToken.isBlank()) payload.put("providerApiToken", providerApiToken);
            if (registryPassword != null && !registryPassword.isBlank()) payload.put("registryPassword", registryPassword);
            RegistryCredentialPolicy.validate(payload.path("imageRepository").asText(""), payload.path("registryPassword").asText(""));
            if ("EXPIRED".equals(job.getStatus())) validate(json.treeToValue(payload, OnboardingRequest.class));
            if (job.getStage() >= 3 && registryPassword != null && !registryPassword.isBlank()) {
                providers.refreshRegistryCredentials(json.treeToValue(payload, OnboardingRequest.class), job.getResourceId());
            }
            jobs.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<OnboardingJob>()
                    .eq("id", job.getId()).set("request_cipher", cipher.encrypt(payload.toString()))
                    .set("status", "PENDING").set("error_message", null).set("updated_at", now()).set("credentials_updated_at", now()));
            if (job.getStage() >= 3 && providerApiToken != null && !providerApiToken.isBlank()) {
                configurations.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.devpilot.server.cicd.entity.CicdConfigurationEntity>()
                        .eq("application_id", applicationId).set("provider_api_token_cipher", cipher.encrypt(providerApiToken))
                        .set("provider_verified_at", null).set("provider_verification_error", null));
            }
            return get(applicationId);
        } catch (BusinessException failure) { throw failure; }
        catch (Exception failure) { throw BusinessException.badRequest(40070, "无法更新接入凭据"); }
    }

    public Status advance(Long applicationId, DevPilotPrincipal principal) {
        var job = require(applicationId);
        String lease = UUID.randomUUID().toString();
        if (jobs.claim(job.getId(), lease, now(), now().plusMinutes(10)) == 0) return get(applicationId);
        job = jobs.selectById(job.getId());
        try {
            var request = json.readValue(cipher.decrypt(job.getRequestCipher()), OnboardingRequest.class);
            var app = applications.selectById(applicationId);
            if (app == null) throw new IllegalArgumentException("应用已删除");
            switch (job.getStage()) {
                case 0 -> {
                    var repo = repositories.inspect(request.repositoryProvider(), request.repositoryUrl(), request.repositoryToken());
                    if (!request.branch().equals(repo.branch())) throw new IllegalArgumentException("默认分支已变化，请重新核对接入计划");
                    providers.checkPermissions(request);
                    var discovery = providers.discover(request.deploymentProvider(), request.providerBaseUrl(), request.providerApiToken());
                    if (!"__new__".equals(request.environmentId()) && discovery.targets().stream().noneMatch(t -> t.projectId().equals(request.projectId()) && t.environmentId().equals(request.environmentId()))) {
                        throw new IllegalArgumentException("所选项目环境不可访问，请检查部署平台授权");
                    }
                    if (discovery.servers().stream().noneMatch(s -> s.id().equals(request.providerServerId() == null ? "" : request.providerServerId()))) {
                        throw new IllegalArgumentException("所选部署服务器不可访问");
                    }
                    providers.checkPublishedPort(request);
                    hostPorts.check(app.getServerId(), request.hostPort());
                }
                case 1 -> {
                    var targetRequest = request;
                    if ("__new__".equals(request.environmentId())) {
                        var target = providers.ensureDedicatedProject(request, app.getCode(), job.getId());
                        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
                        payload.put("projectId", target.projectId()); payload.put("environmentId", target.environmentId());
                        targetRequest = json.treeToValue(payload, OnboardingRequest.class);
                        job.setRequestCipher(encrypt(targetRequest));
                    }
                    var resource = providers.ensureApplication(targetRequest, app.getCode(), job.getId());
                    job.setResourceId(resource.id()); job.setRuntimeKey(resource.runtimeKey());
                }
                case 2 -> {
                    providers.configure(request, job.getResourceId());
                    cicd.saveConfiguration(applicationId, new CicdConfigurationRequest(request.repositoryProvider(),
                            request.repositoryUrl(), request.branch(), request.deploymentProvider(), "API", null,
                            request.providerBaseUrl(), request.providerApiToken(), job.getResourceId(), true, true,
                            false, 300, false, null, 72, false, false), principal);
                    // Scoped column update cannot overwrite a concurrent Agent health report.
                    applications.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.devpilot.server.application.entity.ApplicationEntity>()
                            .eq("id", applicationId).set("runtime_key", job.getRuntimeKey())
                            .set("health_check_url", "http://127.0.0.1:" + request.hostPort() + request.healthPath()));
                    verifyConnection(applicationId);
                }
                case 3 -> {
                    var repo = repositories.inspect(request.repositoryProvider(), request.repositoryUrl(), request.repositoryToken());
                    var configuration = configurations.selectByApplicationId(applicationId);
                    repositories.configureSecrets(request, repo, app.getCode(), cipher.decrypt(configuration.getCallbackSecretCipher()));
                }
                case 4 -> {
                    var repo = repositories.inspect(request.repositoryProvider(), request.repositoryUrl(), request.repositoryToken());
                    job.setChangeUrl(repositories.proposeWorkflow(request, repo, app.getCode(), job.getId()));
                    job.setRequestCipher(null); // Long-lived repository tokens are unnecessary after onboarding.
                }
                default -> throw new IllegalStateException("未知接入阶段");
            }
            job.setStage(job.getStage() + 1);
            job.setStatus(job.getStage() == 5 ? "AWAITING_MERGE" : "PENDING");
            job.setErrorMessage(null);
        } catch (Exception failure) {
            job.setStatus("FAILED");
            String message = failure instanceof OnboardingHttpClient.RemoteFailure || failure instanceof IllegalArgumentException
                    ? failure.getMessage() : "接入步骤失败，已保留进度；请检查配置与网络后重试";
            job.setErrorMessage(message == null ? "接入失败" : message.substring(0, Math.min(1800, message.length())));
        }
        job.setUpdatedAt(now()); jobs.finish(job, lease);
        return get(applicationId);
    }

    public void verifyConnection(Long applicationId) {
        var configuration = configurations.selectByApplicationId(applicationId);
        if (configuration == null || !"API".equals(configuration.getDeploymentMode())) {
            throw BusinessException.badRequest(40070, "请先配置 API 部署目标；Webhook 无法只读验证部署资源");
        }
        String error = null;
        try {
            providers.verify(configuration.getDeploymentProvider(), cipher.decrypt(configuration.getProviderBaseUrlCipher()),
                    cipher.decrypt(configuration.getProviderApiTokenCipher()), configuration.getProviderResourceId());
        } catch (Exception failure) { error = "部署平台连接验证失败，请检查地址、API Key 和应用 ID"; }
        configurations.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.devpilot.server.cicd.entity.CicdConfigurationEntity>()
                .eq("id", configuration.getId()).eq("updated_at", configuration.getUpdatedAt())
                .set("provider_verified_at", error == null ? now() : null).set("provider_verification_error", error));
        if (error != null) throw BusinessException.badRequest(40071, error);
    }

    private void validate(OnboardingRequest request) {
        if (request.providerBaseUrl() == null || request.providerBaseUrl().isBlank() || request.providerApiToken() == null || request.providerApiToken().isBlank()) {
            throw BusinessException.badRequest(40070, "接入计划缺少部署平台地址或授权");
        }
        if ("DOKPLOY".equals(request.deploymentProvider()) && !Boolean.TRUE.equals(request.providerQuotaConfirmed())) {
            throw BusinessException.badRequest(40070, "请先在 Dokploy 核对 Key 配额和有效期，并明确确认；系统不能自动读取剩余额度");
        }
        boolean registryUser = request.registryUsername() != null && !request.registryUsername().isBlank();
        boolean registryPassword = request.registryPassword() != null && !request.registryPassword().isBlank();
        if (registryUser != registryPassword) throw BusinessException.badRequest(40070, "Registry 用户名与密码必须一起提供");
        RegistryCredentialPolicy.validate(request.imageRepository(), request.registryPassword());
        if ("COOLIFY".equals(request.deploymentProvider()) && registryPassword) {
            throw BusinessException.badRequest(40070, "Coolify 使用目标服务器的 Docker 登录凭据；请预先登录 Registry，或选择可自动配置私有镜像凭据的 Dokploy");
        }
        String base = OnboardingHttpClient.origin(request.publicBaseUrl(), true);
        String host = URI.create(base).getHost();
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost") || host.equals("::1") || host.startsWith("127.")) {
            throw BusinessException.badRequest(40070, "云端 CI 无法访问 localhost；请配置可达的 HTTPS 回调域名");
        }
        if (!base.equals(request.publicBaseUrl().replaceAll("/+$", ""))) {
            throw BusinessException.badRequest(40070, "回调地址请填写 DevPilot 的 HTTPS 根地址，不含路径");
        }
        if (!request.imageRepository().matches("[a-z0-9][a-z0-9.-]*(?::[0-9]+)?/[a-z0-9._/-]+")) {
            throw BusinessException.badRequest(40070, "镜像仓库格式无效，不应包含 tag 或 digest");
        }
        if (request.workflowContent() == null || request.workflowContent().isBlank()) {
            throw BusinessException.badRequest(40070, "请先生成并确认接入配置预览");
        }
        if (request.environmentValues() != null && request.environmentValues().values().stream().anyMatch(v -> v == null)) {
            throw BusinessException.badRequest(40070, "环境变量不能是 null");
        }
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void clearExpiredCredentials() {
        var cutoff = now().minusHours(24);
        for (var candidate : jobs.expiring(cutoff, now())) {
            var job = jobs.lockApplication(candidate.getApplicationId());
            if (job == null || "EXPIRED".equals(job.getStatus()) || job.getRequestCipher() == null
                    || job.getCredentialsUpdatedAt() == null || !job.getCredentialsUpdatedAt().isBefore(cutoff)
                    || (job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now()))) continue;
            String plan = null;
            try {
                var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(cipher.decrypt(job.getRequestCipher()));
                payload.put("repositoryToken", ""); payload.put("providerApiToken", ""); payload.put("registryPassword", "");
                if (payload.path("environmentValues").isObject()) {
                    var values = json.createObjectNode();
                    payload.path("environmentValues").fieldNames().forEachRemaining(key -> values.put(key, ""));
                    payload.set("environmentValues", values);
                }
                plan = cipher.encrypt(payload.toString());
            } catch (Exception ignored) { /* Unreadable credentials are cleared rather than retained. */ }
            jobs.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<OnboardingJob>()
                    .eq("id", job.getId()).set("request_cipher", plan).set("status", "EXPIRED")
                    .set("error_message", "临时凭据已清除；请重新授权后继续原任务").set("updated_at", now()));
        }
    }
    private String encrypt(OnboardingRequest request) {
        try { return cipher.encrypt(json.writeValueAsString(request)); }
        catch (Exception failure) { throw BusinessException.badRequest(40070, "接入参数无法保存"); }
    }
    private OnboardingJob require(Long applicationId) {
        var job = jobs.byApplication(applicationId);
        if (job == null) throw BusinessException.notFound(40470, "没有接入任务");
        return job;
    }
    private Status status(OnboardingJob job) {
        Integer containerPort = null, hostPort = null;
        String healthPath = null;
        java.util.Set<String> names = java.util.Set.of();
        boolean registryRequired = false;
        if (job.getRequestCipher() != null) {
            try {
                var request = json.readValue(cipher.decrypt(job.getRequestCipher()), OnboardingRequest.class);
                containerPort = request.containerPort(); hostPort = request.hostPort(); healthPath = request.healthPath();
                if (request.environmentValues() != null && job.getStage() <= 2) names = java.util.Set.copyOf(request.environmentValues().keySet());
                registryRequired = request.registryUsername() != null && !request.registryUsername().isBlank();
            } catch (Exception ignored) { /* Do not expose encrypted payload or decryption details in status. */ }
        }
        return new Status(job.getId(), job.getApplicationId(), job.getStage(), job.getStatus(),
                job.getResourceId(), job.getChangeUrl(), job.getErrorMessage(), job.getUpdatedAt(), containerPort, hostPort, healthPath, names, registryRequired);
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}

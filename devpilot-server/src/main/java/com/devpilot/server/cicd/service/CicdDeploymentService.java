package com.devpilot.server.cicd.service;

import com.devpilot.server.application.entity.ApplicationDeploymentEntity;
import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationDeploymentMapper;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.dto.CicdDeploymentResponse;
import com.devpilot.server.cicd.dto.CicdActivityResponse;
import com.devpilot.server.cicd.entity.CicdConfigurationEntity;
import com.devpilot.server.cicd.entity.CicdDeploymentEntity;
import com.devpilot.server.cicd.entity.CicdPipelineRunEntity;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.cicd.mapper.CicdDeploymentMapper;
import com.devpilot.server.cicd.mapper.CicdPipelineRunMapper;
import com.devpilot.server.cicd.service.DeploymentWebhookClient.DeploymentState;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.metric.service.MetricService;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.devpilot.server.node.dto.ServerNodeResponse;
import com.devpilot.server.node.service.ServerNodeService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CicdDeploymentService {
    private static final int HEALTH_STABILIZATION_SECONDS = 15;
    private static final long GIBIBYTE = 1024L * 1024L * 1024L;
    private static final double DEPLOYMENT_DISK_LIMIT_PERCENT = 95.0;
    private static final long DEPLOYMENT_MINIMUM_FREE_BYTES = 2L * GIBIBYTE;
    private final CicdDeploymentMapper deploymentMapper;
    private final CicdPipelineRunMapper pipelineMapper;
    private final CicdConfigurationMapper configurationMapper;
    private final ApplicationMapper applicationMapper;
    private final ApplicationDeploymentMapper applicationDeploymentMapper;
    private final DeploymentWebhookClient providerClient;
    private final SensitiveSettingCipher cipher;
    private final ServerNodeService serverNodeService;
    private final MetricService metricService;

    @Transactional
    public void requestRelease(CicdConfigurationEntity configuration, CicdPipelineRunEntity run) {
        ApplicationEntity application = lockApplication(configuration.getApplicationId());
        CicdDeploymentEntity active = deploymentMapper.selectActive(configuration.getApplicationId());
        if (active != null) {
            queue(run, "已有发布正在执行，完成后将自动继续（deployment " + active.getId() + "）");
            return;
        }
        String storageBlock = storageBlock(application);
        if (storageBlock != null) {
            queue(run, storageBlock);
            return;
        }
        startRelease(configuration, run);
    }

    private void startRelease(CicdConfigurationEntity configuration, CicdPipelineRunEntity run) {
        CicdDeploymentEntity previous = deploymentMapper.selectLatestHealthy(run.getApplicationId());
        CicdDeploymentEntity deployment = create(configuration, run.getId(), null, "RELEASE", run.getImageUri(),
                previous == null ? null : previous.getImageUri(), configuration.getCreatedBy());
        run.setDeployStatus("TRIGGERING");
        run.setDeployError(null);
        run.setUpdatedAt(now());
        pipelineMapper.updateById(run);
        triggerProvider(configuration, deployment, run);
    }

    public List<CicdDeploymentResponse> list(Long applicationId) {
        requireApplication(applicationId);
        return deploymentMapper.selectRecent(applicationId, 100).stream()
                .map(CicdDeploymentService::toResponse).toList();
    }

    public List<CicdActivityResponse> activity(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return deploymentMapper.selectRecentAll(limit).stream().map(this::toActivityResponse).toList();
    }

    @Transactional
    public CicdDeploymentResponse rollback(Long applicationId, Long targetDeploymentId, DevPilotPrincipal principal) {
        requireApplication(applicationId);
        lockApplication(applicationId);
        CicdDeploymentEntity active = deploymentMapper.selectActive(applicationId);
        if (active != null) {
            throw BusinessException.conflict(40941, "当前已有发布或回滚正在执行，请完成后再操作");
        }
        CicdConfigurationEntity configuration = requireConfiguration(applicationId);
        CicdDeploymentEntity target = deploymentMapper.selectById(targetDeploymentId);
        if (target == null || !applicationId.equals(target.getApplicationId()) || !"HEALTHY".equals(target.getStatus())) {
            throw BusinessException.badRequest(40047, "只能回滚到该应用已验证健康的部署版本");
        }
        CicdDeploymentEntity current = deploymentMapper.selectLatest(applicationId);
        if (current != null && target.getImageUri().equals(current.getImageUri()) && "HEALTHY".equals(current.getStatus())) {
            throw BusinessException.conflict(40940, "目标镜像已经是当前健康版本");
        }
        CicdDeploymentEntity rollback = create(configuration, target.getPipelineRunId(),
                current == null ? null : current.getId(), "ROLLBACK", target.getImageUri(),
                current == null ? null : current.getImageUri(), principal.userId());
        triggerProvider(configuration, rollback, null);
        return toResponse(rollback);
    }

    @Scheduled(fixedDelayString = "${devpilot.cicd.health-reconcile-interval:10s}",
            initialDelayString = "${devpilot.cicd.health-reconcile-initial-delay:20s}")
    @Transactional
    public void reconcileTriggered() {
        LocalDateTime timestamp = now();
        for (CicdDeploymentEntity candidate : deploymentMapper.selectTriggered()) {
            ApplicationEntity application = applicationMapper.selectByIdForUpdate(candidate.getApplicationId());
            if (application == null) continue;
            CicdDeploymentEntity deployment = deploymentMapper.selectById(candidate.getId());
            if (deployment == null || !active(deployment.getStatus())) continue;
            refreshProviderLogs(deployment);
            CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(deployment.getApplicationId());
            if (configuration != null && "API".equals(valueOr(configuration.getDeploymentMode(), "WEBHOOK"))
                    && "DOKPLOY".equals(configuration.getDeploymentProvider())) {
                DeploymentState providerState = providerClient.fetchDeploymentState(
                        configuration.getDeploymentProvider(), decrypt(configuration.getProviderBaseUrlCipher()),
                        decrypt(configuration.getProviderApiTokenCipher()), configuration.getProviderResourceId(),
                        deployment.getProviderDeploymentId());
                if (providerState == DeploymentState.FAILED) {
                    markUnhealthyAndRollback(deployment, application, timestamp, "Deployment provider reported FAILED");
                    continue;
                }
                if (providerState != DeploymentState.SUCCEEDED) {
                    if (!timestamp.isBefore(deployment.getHealthDeadlineAt())) {
                        markUnhealthyAndRollback(deployment, application, timestamp,
                                "Deployment provider did not complete before the health deadline");
                    }
                    continue;
                }
                if ("TRIGGERED".equals(deployment.getStatus())) {
                    deployment.setStatus("VERIFYING");
                    deployment.setUpdatedAt(timestamp);
                    deployment.setLogs(append(deployment.getLogs(),
                            "Deployment provider completed; waiting for a fresh Agent health probe"));
                    deploymentMapper.updateById(deployment);
                    continue;
                }
            }
            boolean freshProbe = application.getHealthCheckedAt() != null
                    && application.getHealthCheckedAt().isAfter("VERIFYING".equals(deployment.getStatus())
                    ? deployment.getUpdatedAt() : deployment.getStartedAt().plusSeconds(HEALTH_STABILIZATION_SECONDS));
            if (freshProbe && "HEALTHY".equals(application.getHealthStatus())) {
                markHealthy(deployment, application, timestamp);
            } else if ((freshProbe && "UNHEALTHY".equals(application.getHealthStatus()))
                    || !timestamp.isBefore(deployment.getHealthDeadlineAt())) {
                markUnhealthyAndRollback(deployment, application, timestamp,
                        freshProbe ? "Application health check reported UNHEALTHY" : "Health verification timed out");
            }
        }
    }

    @Scheduled(fixedDelayString = "${devpilot.cicd.queue-reconcile-interval:10s}",
            initialDelayString = "${devpilot.cicd.queue-reconcile-initial-delay:25s}")
    @Transactional
    public void reconcileQueued() {
        for (Long applicationId : pipelineMapper.selectQueuedApplicationIds()) {
            ApplicationEntity application = applicationMapper.selectByIdForUpdate(applicationId);
            if (application == null) continue;
            if (deploymentMapper.selectActive(applicationId) != null) continue;
            CicdPipelineRunEntity run = pipelineMapper.selectOldestQueued(applicationId);
            if (run == null) continue;
            CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(applicationId);
            if (configuration == null || configuration.getAutoDeploy() != 1) {
                run.setDeployStatus("NOT_STARTED");
                run.setDeployError("自动部署已关闭；本次排队发布未执行");
                run.setUpdatedAt(now());
                pipelineMapper.updateById(run);
                continue;
            }
            String storageBlock = storageBlock(application);
            if (storageBlock != null) {
                queue(run, storageBlock);
                continue;
            }
            startRelease(configuration, run);
        }
    }

    private void refreshProviderLogs(CicdDeploymentEntity deployment) {
        CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(deployment.getApplicationId());
        if (configuration == null || !"API".equals(valueOr(configuration.getDeploymentMode(), "WEBHOOK"))) return;
        try {
            String providerLogs = providerClient.fetchLogs(configuration.getDeploymentProvider(),
                    decrypt(configuration.getProviderBaseUrlCipher()), decrypt(configuration.getProviderApiTokenCipher()),
                    configuration.getProviderResourceId(), deployment.getProviderDeploymentId());
            if (providerLogs != null && !providerLogs.isBlank()) {
                deployment.setLogs(truncate("Deployment accepted by " + configuration.getDeploymentProvider()
                        + "\n--- Provider logs ---\n" + providerLogs, 48000));
                deploymentMapper.updateById(deployment);
            }
        } catch (IllegalStateException ignored) {
            // Provider log collection is best-effort and must not replace the Agent health verdict.
        }
    }

    private CicdDeploymentEntity create(CicdConfigurationEntity configuration, Long pipelineRunId, Long rollbackOfId,
                                        String kind, String imageUri, String previousImageUri, Long triggeredBy) {
        LocalDateTime timestamp = now();
        CicdDeploymentEntity deployment = new CicdDeploymentEntity();
        deployment.setApplicationId(configuration.getApplicationId());
        deployment.setPipelineRunId(pipelineRunId);
        deployment.setRollbackOfId(rollbackOfId);
        deployment.setDeploymentKind(kind);
        deployment.setProvider(configuration.getDeploymentProvider());
        deployment.setImageUri(imageUri);
        deployment.setPreviousImageUri(previousImageUri);
        deployment.setStatus("TRIGGERING");
        deployment.setTriggeredBy(triggeredBy);
        deployment.setStartedAt(timestamp);
        int timeout = configuration.getHealthTimeoutSeconds() == null ? 120 : configuration.getHealthTimeoutSeconds();
        deployment.setHealthDeadlineAt(timestamp.plusSeconds(timeout));
        deployment.setUpdatedAt(timestamp);
        deploymentMapper.insert(deployment);
        return deployment;
    }

    private void triggerProvider(CicdConfigurationEntity configuration, CicdDeploymentEntity deployment,
                                 CicdPipelineRunEntity run) {
        try {
            String mode = valueOr(configuration.getDeploymentMode(), "WEBHOOK");
            String externalId = providerClient.deploy(configuration.getDeploymentProvider(), mode,
                    decrypt(configuration.getDeploymentWebhookCipher()), decrypt(configuration.getProviderBaseUrlCipher()),
                    decrypt(configuration.getProviderApiTokenCipher()), configuration.getProviderResourceId(),
                    deployment.getImageUri());
            deployment.setProviderDeploymentId(externalId);
            deployment.setStatus("TRIGGERED");
            deployment.setLogs(append(deployment.getLogs(), "Deployment accepted by " + configuration.getDeploymentProvider()));
            if (run != null) run.setDeployStatus("TRIGGERED");
        } catch (IllegalStateException exception) {
            deployment.setStatus("FAILED");
            deployment.setLogs(append(deployment.getLogs(), safe(exception.getMessage())));
            deployment.setCompletedAt(now());
            if (run != null) {
                run.setDeployStatus("FAILED");
                run.setDeployError(safe(exception.getMessage()));
            }
        }
        deployment.setUpdatedAt(now());
        deploymentMapper.updateById(deployment);
        if (run != null) {
            run.setUpdatedAt(now());
            pipelineMapper.updateById(run);
        }
    }

    private void markHealthy(CicdDeploymentEntity deployment, ApplicationEntity application, LocalDateTime timestamp) {
        deployment.setStatus("HEALTHY");
        deployment.setLogs(append(deployment.getLogs(), "Health verification passed: " + application.getHealthMessage()));
        deployment.setCompletedAt(timestamp);
        deployment.setUpdatedAt(timestamp);
        deploymentMapper.updateById(deployment);
        application.setCurrentVersion(versionOf(deployment.getImageUri()));
        application.setLastDeployedAt(timestamp);
        application.setUpdatedAt(timestamp);
        applicationMapper.updateById(application);
        recordApplicationDeployment(deployment, application, "SUCCESS", deployment.getLogs(), timestamp);
        updatePipeline(deployment.getPipelineRunId(), "HEALTHY", null);
    }

    private void markUnhealthyAndRollback(CicdDeploymentEntity deployment, ApplicationEntity application,
                                          LocalDateTime timestamp, String reason) {
        deployment.setStatus("UNHEALTHY");
        deployment.setLogs(append(deployment.getLogs(), reason));
        deployment.setCompletedAt(timestamp);
        deployment.setUpdatedAt(timestamp);
        deploymentMapper.updateById(deployment);
        recordApplicationDeployment(deployment, application, "FAILED", deployment.getLogs(), timestamp);
        updatePipeline(deployment.getPipelineRunId(), "HEALTH_FAILED", reason);

        CicdConfigurationEntity configuration = requireConfiguration(application.getId());
        if (configuration.getAutoRollback() != null && configuration.getAutoRollback() == 0) return;
        if ("ROLLBACK".equals(deployment.getDeploymentKind())) return;
        CicdDeploymentEntity target = deploymentMapper.selectLatestHealthy(application.getId());
        if (target == null || target.getImageUri().equals(deployment.getImageUri())) return;
        CicdDeploymentEntity rollback = create(configuration, target.getPipelineRunId(), deployment.getId(),
                "ROLLBACK", target.getImageUri(), deployment.getImageUri(), configuration.getCreatedBy());
        deployment.setStatus("ROLLBACK_TRIGGERED");
        deployment.setUpdatedAt(now());
        deploymentMapper.updateById(deployment);
        triggerProvider(configuration, rollback, null);
    }

    private void recordApplicationDeployment(CicdDeploymentEntity deployment, ApplicationEntity application,
                                             String result, String logs, LocalDateTime timestamp) {
        CicdConfigurationEntity configuration = requireConfiguration(application.getId());
        ApplicationDeploymentEntity record = new ApplicationDeploymentEntity();
        record.setApplicationId(application.getId());
        record.setVersion(versionOf(deployment.getImageUri()));
        record.setServerId(application.getServerId());
        record.setDockerImage(deployment.getImageUri());
        record.setOperatorId(deployment.getTriggeredBy() == null ? configuration.getCreatedBy() : deployment.getTriggeredBy());
        record.setDeployedAt(timestamp);
        record.setResult(result);
        record.setLogs(truncate(logs, 8000));
        applicationDeploymentMapper.insert(record);
    }

    private void updatePipeline(Long pipelineRunId, String status, String error) {
        if (pipelineRunId == null) return;
        CicdPipelineRunEntity run = pipelineMapper.selectById(pipelineRunId);
        if (run == null) return;
        run.setDeployStatus(status);
        run.setDeployError(truncate(error, 1000));
        run.setUpdatedAt(now());
        pipelineMapper.updateById(run);
    }

    private ApplicationEntity requireApplication(Long applicationId) {
        ApplicationEntity application = applicationMapper.selectById(applicationId);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
        return application;
    }

    private ApplicationEntity lockApplication(Long applicationId) {
        ApplicationEntity application = applicationMapper.selectByIdForUpdate(applicationId);
        if (application == null) {
            throw BusinessException.notFound(40420, "应用不存在");
        }
        return application;
    }

    private void queue(CicdPipelineRunEntity run, String reason) {
        run.setDeployStatus("QUEUED");
        run.setDeployError(truncate(reason, 1000));
        run.setUpdatedAt(now());
        pipelineMapper.updateById(run);
    }

    private String storageBlock(ApplicationEntity application) {
        MetricPointResponse metric = metricService.current(application.getServerId());
        if (metric == null || metric.timestamp() == null || metric.timestamp().isBefore(now().minusMinutes(10))) {
            return null;
        }
        boolean percentCritical = metric.diskUsage() != null
                && metric.diskUsage() >= DEPLOYMENT_DISK_LIMIT_PERCENT;
        boolean freeSpaceCritical = metric.diskTotal() != null && metric.diskTotal() >= 10L * GIBIBYTE
                && metric.diskFree() != null && metric.diskFree() < DEPLOYMENT_MINIMUM_FREE_BYTES;
        if (!percentCritical && !freeSpaceCritical) return null;
        String usage = metric.diskUsage() == null ? "未知" : String.format(java.util.Locale.ROOT, "%.1f", metric.diskUsage());
        return "磁盘保护已暂停发布：当前使用率 " + usage
                + "%、可用 " + formatGiB(metric.diskFree()) + " GiB；清理后会自动继续";
    }

    private static String formatGiB(Long bytes) {
        if (bytes == null) return "未知";
        return String.format(java.util.Locale.ROOT, "%.1f", bytes.doubleValue() / GIBIBYTE);
    }

    private CicdConfigurationEntity requireConfiguration(Long applicationId) {
        CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(applicationId);
        if (configuration == null) throw BusinessException.notFound(40440, "CI/CD 配置不存在");
        return configuration;
    }

    private String decrypt(String value) {
        return value == null ? null : cipher.decrypt(value);
    }

    private static CicdDeploymentResponse toResponse(CicdDeploymentEntity entity) {
        return new CicdDeploymentResponse(entity.getId(), entity.getApplicationId(), entity.getPipelineRunId(),
                entity.getRollbackOfId(), entity.getDeploymentKind(), entity.getProvider(), entity.getImageUri(),
                entity.getPreviousImageUri(), entity.getStatus(), entity.getProviderDeploymentId(), entity.getLogs(),
                entity.getTriggeredBy(), entity.getStartedAt(), entity.getHealthDeadlineAt(), entity.getCompletedAt(),
                entity.getUpdatedAt());
    }

    private CicdActivityResponse toActivityResponse(CicdDeploymentEntity entity) {
        ApplicationEntity application = applicationMapper.selectById(entity.getApplicationId());
        if (application == null) {
            return new CicdActivityResponse(entity.getId(), entity.getApplicationId(), "已删除应用", "UNKNOWN",
                    null, "未知服务器", entity.getDeploymentKind(), entity.getProvider(), entity.getImageUri(),
                    entity.getStatus(), excerpt(entity.getLogs()), entity.getStartedAt(), entity.getCompletedAt(),
                    entity.getUpdatedAt());
        }
        ServerNodeResponse server = serverNodeService.get(application.getServerId());
        return new CicdActivityResponse(entity.getId(), entity.getApplicationId(), application.getName(),
                application.getEnvironment(), application.getServerId(), server.name(), entity.getDeploymentKind(),
                entity.getProvider(), entity.getImageUri(), entity.getStatus(), excerpt(entity.getLogs()),
                entity.getStartedAt(), entity.getCompletedAt(), entity.getUpdatedAt());
    }

    private static String versionOf(String imageUri) {
        int digest = imageUri.lastIndexOf("@sha256:");
        if (digest > 0) return "sha256:" + imageUri.substring(digest + 8, Math.min(imageUri.length(), digest + 20));
        int slash = imageUri.lastIndexOf('/');
        int colon = imageUri.lastIndexOf(':');
        return colon > slash ? imageUri.substring(colon + 1) : imageUri;
    }

    private static String append(String current, String line) {
        String value = current == null || current.isBlank() ? line : current + "\n" + line;
        return truncate(value, 48000);
    }

    private static String safe(String value) {
        return truncate(value == null || value.isBlank() ? "Deployment provider request failed" : value, 1000);
    }

    private static String excerpt(String value) {
        if (value == null || value.isBlank()) return null;
        String compact = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return truncate(compact, 240);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean active(String status) {
        return "TRIGGERING".equals(status) || "TRIGGERED".equals(status) || "VERIFYING".equals(status);
    }

    private static String truncate(String value, int maximum) {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

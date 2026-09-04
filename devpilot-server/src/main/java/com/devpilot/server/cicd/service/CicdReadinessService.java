package com.devpilot.server.cicd.service;

import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.dto.CicdReadinessCheckResponse;
import com.devpilot.server.cicd.dto.CicdReadinessResponse;
import com.devpilot.server.cicd.entity.ApplicationEnvironmentStateEntity;
import com.devpilot.server.cicd.entity.CicdConfigurationEntity;
import com.devpilot.server.cicd.entity.CicdDeploymentEntity;
import com.devpilot.server.cicd.entity.CicdPipelineRunEntity;
import com.devpilot.server.cicd.mapper.ApplicationEnvironmentStateMapper;
import com.devpilot.server.cicd.mapper.ApplicationEnvironmentVariableMapper;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.cicd.mapper.CicdDeploymentMapper;
import com.devpilot.server.cicd.mapper.CicdPipelineRunMapper;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.metric.service.MetricService;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CicdReadinessService {
    private static final long GIBIBYTE = 1024L * 1024L * 1024L;
    private static final double DEPLOYMENT_DISK_LIMIT_PERCENT = 95.0;
    private static final long DEPLOYMENT_MINIMUM_FREE_BYTES = 2L * GIBIBYTE;
    private final ApplicationMapper applicationMapper;
    private final ServerNodeMapper serverMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final CicdConfigurationMapper configurationMapper;
    private final ApplicationEnvironmentStateMapper environmentStateMapper;
    private final ApplicationEnvironmentVariableMapper environmentVariableMapper;
    private final CicdDeploymentMapper deploymentMapper;
    private final CicdPipelineRunMapper pipelineMapper;
    private final MetricService metricService;

    public CicdReadinessResponse inspect(Long applicationId) {
        LocalDateTime timestamp = now();
        ApplicationEntity application = applicationMapper.selectById(applicationId);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
        CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(applicationId);
        List<CicdReadinessCheckResponse> checks = new ArrayList<>();
        checks.add(configurationCheck(configuration));
        checks.add(providerCheck(configuration));
        checks.add(callbackCheck(configuration));
        checks.add(automationCheck(configuration));

        ServerNodeEntity server = serverMapper.selectActiveById(application.getServerId());
        checks.add(serverCheck(server));
        checks.add(healthCheck(application, timestamp));
        checks.add(capacityCheck(application, timestamp));
        checks.add(environmentCheck(applicationId, configuration));
        checks.add(concurrencyCheck(applicationId));
        checks.add(runtimeCheck(application));
        checks.add(artifactCheck(applicationId));

        int blockers = (int) checks.stream().filter(check -> "BLOCK".equals(check.status())).count();
        int warnings = (int) checks.stream().filter(check -> "WARN".equals(check.status())).count();
        long passes = checks.stream().filter(check -> "PASS".equals(check.status())).count();
        int score = (int) Math.round((passes + warnings * 0.6) * 100.0 / checks.size());
        String summary = blockers > 0 ? blockers + " 个阻断项需要处理"
                : warnings > 0 ? "可以自动发布，另有 " + warnings + " 个建议项"
                : "所有发布前检查均已通过";
        return new CicdReadinessResponse(applicationId, blockers == 0, score, blockers, warnings,
                summary, timestamp, checks);
    }

    public String blockingReason(Long applicationId) {
        List<CicdReadinessCheckResponse> blockers = inspect(applicationId).checks().stream()
                .filter(check -> "BLOCK".equals(check.status())).toList();
        if (blockers.isEmpty()) return null;
        return "发布前检查未通过：" + blockers.stream()
                .map(check -> check.title() + "（" + check.detail() + "）")
                .reduce((left, right) -> left + "；" + right).orElse("存在阻断项");
    }

    private static CicdReadinessCheckResponse configurationCheck(CicdConfigurationEntity configuration) {
        return configuration == null
                ? check("CONFIGURATION", "BLOCK", "流水线契约", "尚未连接代码仓库与部署平台。", "CONFIGURE_CICD")
                : check("CONFIGURATION", "PASS", "流水线契约",
                configuration.getRepositoryProvider() + " · " + configuration.getBranchName(), null);
    }

    private static CicdReadinessCheckResponse providerCheck(CicdConfigurationEntity configuration) {
        if (configuration == null) {
            return check("PROVIDER", "BLOCK", "部署 Provider", "等待保存 CI/CD 配置。", "CONFIGURE_CICD");
        }
        boolean api = "API".equals(configuration.getDeploymentMode());
        boolean ready = api
                ? configuration.getProviderBaseUrlCipher() != null
                && configuration.getProviderApiTokenCipher() != null
                && configuration.getProviderResourceId() != null
                : configuration.getDeploymentWebhookCipher() != null;
        return ready
                ? check("PROVIDER", "PASS", "部署 Provider",
                configuration.getDeploymentProvider() + " · " + configuration.getDeploymentMode(), null)
                : check("PROVIDER", "BLOCK", "部署 Provider", "部署凭据或资源 ID 不完整。", "CONFIGURE_CICD");
    }

    private static CicdReadinessCheckResponse callbackCheck(CicdConfigurationEntity configuration) {
        boolean ready = configuration != null && configuration.getCallbackSecretCipher() != null;
        return ready
                ? check("CALLBACK", "PASS", "签名回调", "CI 回调密钥已加密配置。", null)
                : check("CALLBACK", "BLOCK", "签名回调", "缺少 CI 回调密钥。", "CONFIGURE_CICD");
    }

    private static CicdReadinessCheckResponse automationCheck(CicdConfigurationEntity configuration) {
        boolean enabled = configuration != null && Integer.valueOf(1).equals(configuration.getAutoDeploy());
        if (!enabled) {
            return check("AUTOMATION", "BLOCK", "自动部署", "自动部署已关闭，流水线成功后不会创建发布。",
                    "CONFIGURE_CICD");
        }
        if (!Integer.valueOf(1).equals(configuration.getProductionApproval())) {
            return check("AUTOMATION", "WARN", "自动部署", "已自动部署，但生产审批未开启。", "CONFIGURE_CICD");
        }
        return check("AUTOMATION", "PASS", "自动部署", "生产审批与门禁通过后进入受控发布。", null);
    }

    private static CicdReadinessCheckResponse serverCheck(ServerNodeEntity server) {
        if (server == null) return check("AGENT", "BLOCK", "目标服务器", "关联服务器不存在。", "CONFIGURE_APPLICATION");
        return "ONLINE".equals(server.getAgentStatus())
                ? check("AGENT", "PASS", "Agent 连接", server.getName() + " 在线，可执行健康探测。", null)
                : check("AGENT", "BLOCK", "Agent 连接",
                server.getName() + " 当前为 " + server.getAgentStatus() + "。", "OPEN_SERVER");
    }

    private static CicdReadinessCheckResponse healthCheck(ApplicationEntity application, LocalDateTime timestamp) {
        if (application.getHealthCheckUrl() == null || application.getHealthCheckUrl().isBlank()) {
            return check("HEALTH", "BLOCK", "健康验证", "未配置 HTTP(S) 健康检查地址，无法确认新版本。",
                    "CONFIGURE_APPLICATION");
        }
        if (application.getHealthCheckedAt() == null) {
            return check("HEALTH", "WARN", "健康验证", "地址已配置，仍在等待 Agent 首次探测。", "OPEN_SERVER");
        }
        if (application.getHealthCheckedAt().isBefore(timestamp.minusMinutes(2))) {
            return check("HEALTH", "WARN", "健康验证", "最近探测已超过 2 分钟，请检查 Agent。", "OPEN_SERVER");
        }
        if (!"HEALTHY".equals(application.getHealthStatus())) {
            return check("HEALTH", "WARN", "健康验证",
                    "当前状态 " + application.getHealthStatus() + "：" + valueOr(application.getHealthMessage(), "暂无详情"),
                    "CONFIGURE_APPLICATION");
        }
        return check("HEALTH", "PASS", "健康验证",
                "最近探测通过：" + valueOr(application.getHealthMessage(), application.getHealthCheckUrl()), null);
    }

    private CicdReadinessCheckResponse capacityCheck(ApplicationEntity application, LocalDateTime timestamp) {
        MetricPointResponse metric = metricService.current(application.getServerId());
        if (metric == null || metric.timestamp() == null || metric.timestamp().isBefore(timestamp.minusMinutes(10))) {
            return check("CAPACITY", "WARN", "磁盘容量", "缺少 10 分钟内的容量数据；发布不会被误阻断。", "OPEN_SERVER");
        }
        boolean percentCritical = metric.diskUsage() != null && metric.diskUsage() >= DEPLOYMENT_DISK_LIMIT_PERCENT;
        boolean freeCritical = metric.diskTotal() != null && metric.diskTotal() >= 10L * GIBIBYTE
                && metric.diskFree() != null && metric.diskFree() < DEPLOYMENT_MINIMUM_FREE_BYTES;
        String detail = "使用率 " + formatPercent(metric.diskUsage()) + " · 可用 " + formatGiB(metric.diskFree()) + " GiB";
        if (percentCritical || freeCritical) {
            return check("CAPACITY", "BLOCK", "磁盘容量", detail + "，磁盘保护将暂停发布。", "OPEN_SERVER");
        }
        if (metric.diskUsage() != null && metric.diskUsage() >= 90.0) {
            return check("CAPACITY", "WARN", "磁盘容量", detail + "，建议发布前清理。", "OPEN_SERVER");
        }
        return check("CAPACITY", "PASS", "磁盘容量", detail, null);
    }

    private CicdReadinessCheckResponse environmentCheck(Long applicationId, CicdConfigurationEntity configuration) {
        int variableCount = environmentVariableMapper.selectByApplicationId(applicationId).size();
        if (variableCount == 0) {
            return check("ENVIRONMENT", "PASS", "运行变量", "未由 DevPilot 托管变量（可选）。", null);
        }
        ApplicationEnvironmentStateEntity state = environmentStateMapper.selectById(applicationId);
        boolean synced = state != null && state.getSyncedRevision() != null
                && state.getSyncedRevision().equals(state.getRevision()) && "SYNCED".equals(state.getSyncStatus());
        if (synced) return check("ENVIRONMENT", "PASS", "运行变量", variableCount + " 个变量已同步。", null);
        boolean safeSync = configuration != null && "COOLIFY".equals(configuration.getDeploymentProvider())
                && "API".equals(configuration.getDeploymentMode());
        if (safeSync && (state == null || !"FAILED".equals(state.getSyncStatus()))) {
            return check("ENVIRONMENT", "WARN", "运行变量",
                    variableCount + " 个变量待同步；发布前会自动安全同步。", "MANAGE_ENVIRONMENT");
        }
        String detail = state != null && state.getSyncError() != null ? state.getSyncError()
                : "当前 Provider 不支持 DevPilot 的安全增量同步。";
        return check("ENVIRONMENT", "BLOCK", "运行变量", detail, "MANAGE_ENVIRONMENT");
    }

    private CicdReadinessCheckResponse concurrencyCheck(Long applicationId) {
        CicdDeploymentEntity active = deploymentMapper.selectActive(applicationId);
        if (active != null) {
            return check("CONCURRENCY", "WARN", "发布并发", "已有部署 " + active.getId() + " 正在执行，新版本将持久排队。",
                    "VIEW_PIPELINES");
        }
        CicdPipelineRunEntity queued = pipelineMapper.selectOldestQueued(applicationId);
        if (queued != null) {
            return check("CONCURRENCY", "WARN", "发布并发", "流水线 " + queued.getExternalRunId() + " 正在等待发布。",
                    "VIEW_PIPELINES");
        }
        return check("CONCURRENCY", "PASS", "发布并发", "当前没有执行中或排队中的版本。", null);
    }

    private CicdReadinessCheckResponse runtimeCheck(ApplicationEntity application) {
        DockerContainerSnapshotEntity container = application.getContainerSnapshotId() == null ? null
                : containerMapper.selectById(application.getContainerSnapshotId());
        if (container == null || !Integer.valueOf(1).equals(container.getActive())) {
            return check("RUNTIME", "WARN", "当前容器", "关联容器不在最新清单中；部署仍可修复运行状态。",
                    "CONFIGURE_APPLICATION");
        }
        if (!"running".equalsIgnoreCase(container.getState())) {
            return check("RUNTIME", "WARN", "当前容器", container.getName() + " 当前为 " + container.getState() + "。",
                    "CONFIGURE_APPLICATION");
        }
        if ("unhealthy".equalsIgnoreCase(container.getHealth())) {
            return check("RUNTIME", "WARN", "当前容器", container.getName() + " 正在运行但 Docker Health 异常。",
                    "CONFIGURE_APPLICATION");
        }
        return check("RUNTIME", "PASS", "当前容器", container.getName() + " 正在运行。", null);
    }

    private CicdReadinessCheckResponse artifactCheck(Long applicationId) {
        List<CicdPipelineRunEntity> runs = pipelineMapper.selectRecent(applicationId, 1);
        if (runs.isEmpty()) {
            return check("ARTIFACT", "WARN", "候选镜像", "尚无流水线记录；推送代码后将验证测试、扫描与不可变镜像。",
                    "VIEW_PIPELINES");
        }
        CicdPipelineRunEntity run = runs.getFirst();
        if ("SUCCEEDED".equals(run.getStatus()) && run.getImageUri() != null) {
            return check("ARTIFACT", "PASS", "候选镜像", run.getCommitSha().substring(0, Math.min(12, run.getCommitSha().length()))
                    + " · " + run.getImageUri(), null);
        }
        return check("ARTIFACT", "WARN", "候选镜像", "最新流水线 " + run.getExternalRunId() + " 为 " + run.getStatus() + "。",
                "VIEW_PIPELINES");
    }

    private static CicdReadinessCheckResponse check(String code, String status, String title, String detail,
                                                     String action) {
        return new CicdReadinessCheckResponse(code, status, title, detail, action);
    }

    private static String formatPercent(Double value) {
        return value == null ? "未知" : String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String formatGiB(Long value) {
        return value == null ? "未知" : String.format(Locale.ROOT, "%.1f", value.doubleValue() / GIBIBYTE);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

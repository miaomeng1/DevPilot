package com.devpilot.server.servicecatalog.service;

import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.audit.service.AuditLogService;
import com.devpilot.server.audit.service.AuditLogService.AuditRecord;
import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import com.devpilot.server.docker.entity.DockerHostSnapshotEntity;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.docker.mapper.DockerHostSnapshotMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.metric.service.MetricService;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.servicecatalog.dto.AgentServiceInstallResultRequest;
import com.devpilot.server.servicecatalog.dto.AgentServiceInstallTaskResponse;
import com.devpilot.server.servicecatalog.dto.CreateServiceInstallationRequest;
import com.devpilot.server.servicecatalog.dto.ServiceInstallationResponse;
import com.devpilot.server.servicecatalog.dto.ServiceTemplateResponse;
import com.devpilot.server.servicecatalog.entity.ServiceInstallationEntity;
import com.devpilot.server.servicecatalog.mapper.ServiceInstallationMapper;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServiceTemplateService {

    private static final long GIBIBYTE = 1024L * 1024 * 1024;
    private static final Map<String, TemplateDefinition> TEMPLATES = templates();
    private final ServiceInstallationMapper installationMapper;
    private final ServerNodeMapper serverNodeMapper;
    private final DockerHostSnapshotMapper dockerHostMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final ApplicationMapper applicationMapper;
    private final AgentRegistrationService registrationService;
    private final UserMapper userMapper;
    private final AuditLogService auditLogService;
    private final MetricService metricService;

    public List<ServiceTemplateResponse> catalog() {
        return TEMPLATES.values().stream().map(TemplateDefinition::response).toList();
    }

    @Transactional
    public List<ServiceInstallationResponse> installations() {
        reconcileDiscovering();
        return installationMapper.selectAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public ServiceInstallationResponse install(String templateId, CreateServiceInstallationRequest request,
                                               DevPilotPrincipal principal) {
        TemplateDefinition template = requireTemplate(templateId);
        ServerNodeEntity server = serverNodeMapper.selectActiveById(request.serverId());
        if (server == null) {
            throw BusinessException.notFound(40460, "服务器不存在");
        }
        if (!"ONLINE".equals(server.getAgentStatus())) {
            throw BusinessException.conflict(40960, "Agent 离线，无法安装服务");
        }
        DockerHostSnapshotEntity docker = dockerHostMapper.selectById(server.getId());
        if (docker == null || docker.getAvailable() != 1) {
            throw BusinessException.conflict(40961, "目标服务器的 Docker 当前不可用");
        }
        validateCapacity(server.getId());
        String instanceName = request.instanceName().trim();
        if (installationMapper.countActiveInstance(server.getId(), instanceName) > 0) {
            throw BusinessException.conflict(40962, "该服务器已有同名服务或安装任务");
        }
        if (installationMapper.countActivePort(server.getId(), request.hostPort()) > 0) {
            throw BusinessException.conflict(40965, "该服务器端口已被另一个模板服务使用");
        }
        String portMarker = ":" + request.hostPort() + "→";
        if (containerMapper.selectActive(server.getId()).stream()
                .anyMatch(container -> container.getPortsJson() != null && container.getPortsJson().contains(portMarker))) {
            throw BusinessException.conflict(40966, "该端口已被服务器上的容器占用");
        }
        if (applicationMapper.selectByCode(instanceName) != null) {
            throw BusinessException.conflict(40963, "实例名已被现有应用编码使用");
        }
        String displayName = request.displayName().trim();
        if (displayName.length() < 2) {
            throw BusinessException.badRequest(40061, "显示名称至少需要 2 个字符");
        }
        try {
            ZoneId.of(request.timezone().trim());
        } catch (DateTimeException exception) {
            throw BusinessException.badRequest(40062, "时区不是有效的 IANA Time Zone");
        }
        LocalDateTime now = now();
        ServiceInstallationEntity entity = new ServiceInstallationEntity();
        entity.setTemplateId(template.id());
        entity.setTemplateName(template.name());
        entity.setImage(template.image());
        entity.setDisplayName(displayName);
        entity.setInstanceName(instanceName);
        entity.setEnvironment(request.environment());
        entity.setServerId(server.getId());
        entity.setRequestedPort(request.hostPort());
        entity.setTimezone(request.timezone().trim());
        entity.setStatus("REQUESTED");
        entity.setRequestedBy(principal.userId());
        entity.setRequestedAt(now);
        entity.setUpdatedAt(now);
        installationMapper.insert(entity);
        return toResponse(entity);
    }

    @Transactional
    public AgentServiceInstallTaskResponse claimNext(String rawToken) {
        Long serverId = registrationService.authenticateActive(rawToken);
        for (int attempt = 0; attempt < 3; attempt++) {
            ServiceInstallationEntity entity = installationMapper.selectNext(serverId);
            if (entity == null) return null;
            LocalDateTime now = now();
            if (installationMapper.claim(entity.getId(), serverId, now) == 1) {
                return new AgentServiceInstallTaskResponse(entity.getId(), entity.getTemplateId(),
                        entity.getInstanceName(), entity.getRequestedPort(), entity.getTimezone());
            }
        }
        return null;
    }

    @Transactional
    public void complete(String rawToken, Long installationId, AgentServiceInstallResultRequest request) {
        Long serverId = registrationService.authenticateActive(rawToken);
        ServiceInstallationEntity installation = installationMapper.selectById(installationId);
        if (installation == null || !serverId.equals(installation.getServerId())) {
            throw BusinessException.notFound(40461, "安装任务不存在");
        }
        boolean succeeded = "SUCCEEDED".equals(request.status());
        String containerId = trimToNull(request.containerId());
        if (succeeded && (containerId == null || !containerId.matches("[a-f0-9]{12,64}"))) {
            throw BusinessException.badRequest(40060, "成功结果必须包含有效的容器 ID");
        }
        if (succeeded && List.of("DISCOVERING", "READY").contains(installation.getStatus())
                && containerId.equals(installation.getContainerId())) {
            return;
        }
        if (!succeeded && "FAILED".equals(installation.getStatus())) {
            return;
        }
        String status = succeeded ? "DISCOVERING" : "FAILED";
        String error = succeeded ? null : trimError(request.errorMessage());
        LocalDateTime now = now();
        if (installationMapper.complete(installationId, serverId, status, containerId,
                succeeded ? installation.getRequestedPort() : null, error, now) != 1) {
            throw BusinessException.conflict(40964, "安装任务已完成或不再可领取");
        }
        auditResult(installation, succeeded, error);
    }

    @Scheduled(fixedDelay = 5_000)
    @Transactional
    public void reconcileInstallations() {
        reconcileDiscovering();
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void failExpiredInstallations() {
        LocalDateTime now = now();
        for (ServiceInstallationEntity entity : installationMapper.selectExpired(now.minusMinutes(15))) {
            DockerContainerSnapshotEntity recovered = containerMapper.selectActive(entity.getServerId()).stream()
                    .filter(container -> ("devpilot-" + entity.getInstanceName()).equals(container.getName()))
                    .filter(container -> entity.getImage().equals(container.getImage()))
                    .filter(container -> "running".equalsIgnoreCase(container.getState()))
                    .findFirst().orElse(null);
            if (recovered != null && installationMapper.complete(entity.getId(), entity.getServerId(),
                    "DISCOVERING", recovered.getContainerId(), entity.getRequestedPort(), null, now) == 1) {
                auditResult(entity, true, null);
                continue;
            }
            String error = "Agent 安装执行超时";
            if (installationMapper.complete(entity.getId(), entity.getServerId(), "FAILED",
                    null, null, error, now) == 1) {
                auditResult(entity, false, error);
            }
        }
    }

    private void reconcileDiscovering() {
        for (ServiceInstallationEntity candidate : installationMapper.selectDiscovering()) {
            ServiceInstallationEntity entity = installationMapper.selectByIdForUpdate(candidate.getId());
            if (entity == null || !"DISCOVERING".equals(entity.getStatus())) continue;
            DockerContainerSnapshotEntity container = containerMapper.selectByDockerId(
                    entity.getServerId(), entity.getContainerId());
            if (container == null || container.getActive() != 1) continue;
            if (!entity.getImage().equals(container.getImage())) {
                entity.setStatus("FAILED");
                entity.setErrorMessage("Agent 模板版本与控制面不一致；请升级 Agent 后重试。实际镜像："
                        + container.getImage());
                entity.setUpdatedAt(now());
                installationMapper.updateById(entity);
                continue;
            }
            ApplicationEntity existing = applicationMapper.selectByCode(entity.getInstanceName());
            if (existing != null) {
                entity.setStatus("FAILED");
                entity.setErrorMessage("安装完成，但应用编码已被占用；容器仍保留在 Docker 中");
                entity.setUpdatedAt(now());
                installationMapper.updateById(entity);
                continue;
            }
            LocalDateTime now = now();
            ApplicationEntity application = new ApplicationEntity();
            application.setName(entity.getDisplayName());
            application.setCode(entity.getInstanceName());
            application.setDescription("由 " + entity.getTemplateName() + " 一键模板创建；公网访问需显式配置 Nginx 反向代理。");
            application.setEnvironment(entity.getEnvironment());
            application.setServerId(entity.getServerId());
            application.setDeployType("TEMPLATE");
            application.setContainerSnapshotId(container.getId());
            application.setCurrentVersion(version(entity.getImage()));
            application.setHealthCheckUrl("http://127.0.0.1:" + entity.getHostPort());
            application.setStatus("running".equalsIgnoreCase(container.getState()) ? "RUNNING" : "OFFLINE");
            application.setHealthStatus("UNKNOWN");
            application.setLastDeployedAt(entity.getCompletedAt());
            application.setCreatedBy(entity.getRequestedBy());
            application.setCreatedAt(now);
            application.setUpdatedAt(now);
            applicationMapper.insert(application);
            entity.setApplicationId(application.getId());
            entity.setStatus("READY");
            entity.setUpdatedAt(now);
            installationMapper.updateById(entity);
        }
    }

    private ServiceInstallationResponse toResponse(ServiceInstallationEntity entity) {
        ServerNodeEntity server = serverNodeMapper.selectActiveById(entity.getServerId());
        return new ServiceInstallationResponse(entity.getId(), entity.getTemplateId(), entity.getTemplateName(),
                entity.getImage(), entity.getDisplayName(), entity.getInstanceName(), entity.getEnvironment(),
                entity.getServerId(), server == null ? "已删除服务器" : server.getName(), entity.getRequestedPort(),
                entity.getHostPort(), entity.getTimezone(), entity.getContainerId(), entity.getApplicationId(),
                entity.getStatus(), entity.getErrorMessage(), entity.getRequestedAt(), entity.getCompletedAt(),
                entity.getUpdatedAt());
    }

    private void auditResult(ServiceInstallationEntity entity, boolean success, String error) {
        UserEntity user = userMapper.selectById(entity.getRequestedBy());
        String parameters = "{\"phase\":\"EXECUTION\",\"templateId\":\"%s\",\"instanceName\":\"%s\",\"hostBinding\":\"127.0.0.1:%d\"}"
                .formatted(entity.getTemplateId(), entity.getInstanceName(), entity.getRequestedPort());
        auditLogService.record(new AuditRecord(entity.getRequestedBy(), user == null ? null : user.getUsername(),
                "INSTALL_SERVICE_TEMPLATE_RESULT", "SERVICE_TEMPLATE", entity.getId().toString(),
                entity.getDisplayName(), entity.getServerId(), null, parameters, success, error));
    }

    private void validateCapacity(Long serverId) {
        MetricPointResponse metric = metricService.current(serverId);
        if (metric == null || metric.timestamp() == null || metric.timestamp().isBefore(now().minusMinutes(10))) {
            return;
        }
        boolean diskCritical = metric.diskUsage() != null && metric.diskUsage() >= 95.0;
        boolean diskFreeCritical = metric.diskTotal() != null && metric.diskTotal() >= 10L * GIBIBYTE
                && metric.diskFree() != null && metric.diskFree() < 2L * GIBIBYTE;
        if (diskCritical || diskFreeCritical) {
            throw BusinessException.conflict(40968, "磁盘保护已阻止安装：请先释放空间再重试");
        }
        if (metric.memoryAvailable() != null && metric.memoryAvailable() < 256L * 1024 * 1024) {
            throw BusinessException.conflict(40969, "可用内存低于 256 MB，已阻止安装以保护现有服务");
        }
    }

    private static TemplateDefinition requireTemplate(String id) {
        TemplateDefinition template = TEMPLATES.get(id);
        if (template == null) throw BusinessException.notFound(40462, "服务模板不存在");
        return template;
    }

    private static Map<String, TemplateDefinition> templates() {
        Map<String, TemplateDefinition> values = new LinkedHashMap<>();
        add(values, new TemplateDefinition("uptime-kuma", "Uptime Kuma", "UK", "监控 Monitoring",
                "轻量可用性监控、状态页与通知中心，适合作为个人服务器的第一项服务。",
                "louislam/uptime-kuma:2.5.0", "2.5.0", 3001, 3001, 805_306_368L,
                List.of("监控配置与历史 /app/data"), "https://github.com/louislam/uptime-kuma/wiki",
                "https://github.com/louislam/uptime-kuma", "打开页面后创建首个管理员；再配置 Nginx 域名与 HTTPS。", "mint"));
        add(values, new TemplateDefinition("gitea", "Gitea", "GT", "开发 Development",
                "自托管 Git、Issue、Pull Request 与 Actions，适合私有代码和轻量团队协作。",
                "gitea/gitea:1.27.2", "1.27.2", 3000, 3100, 1_073_741_824L,
                List.of("仓库与配置 /data"), "https://docs.gitea.com/installation/install-with-docker",
                "https://github.com/go-gitea/gitea", "首次打开完成安装向导；SSH 端口默认不暴露。", "blue"));
        add(values, new TemplateDefinition("audiobookshelf", "Audiobookshelf", "AB", "媒体 Media",
                "个人有声书与播客服务器，支持多用户、进度同步和移动端。",
                "ghcr.io/advplyr/audiobookshelf:2.36.0", "2.36.0", 80, 13378, 1_073_741_824L,
                List.of("配置 /config", "元数据 /metadata", "有声书 /audiobooks", "播客 /podcasts"),
                "https://www.audiobookshelf.org/docs", "https://github.com/advplyr/audiobookshelf",
                "首次打开创建管理员；媒体卷创建后可在服务器上导入文件。", "violet"));
        return Collections.unmodifiableMap(values);
    }

    private static void add(Map<String, TemplateDefinition> values, TemplateDefinition template) {
        values.put(template.id(), template);
    }

    private static String version(String image) {
        int separator = image.lastIndexOf(':');
        return separator > image.lastIndexOf('/') ? image.substring(separator + 1) : image;
    }

    private static String trimError(String value) {
        return value == null || value.isBlank() ? "Agent 安装失败" : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record TemplateDefinition(String id, String name, String shortName, String category, String description,
                                      String image, String version, int containerPort, int recommendedPort,
                                      long memoryLimitBytes, List<String> persistentData, String documentationUrl,
                                      String sourceUrl, String setupHint, String accent) {
        private ServiceTemplateResponse response() {
            return new ServiceTemplateResponse(id, name, shortName, category, description, image, version,
                    containerPort, recommendedPort, memoryLimitBytes, persistentData, documentationUrl,
                    sourceUrl, setupHint, accent);
        }
    }
}

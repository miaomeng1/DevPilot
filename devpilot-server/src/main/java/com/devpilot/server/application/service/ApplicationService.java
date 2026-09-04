package com.devpilot.server.application.service;

import com.devpilot.server.application.dto.AgentHealthResultRequest;
import com.devpilot.server.application.dto.AgentHealthTaskResponse;
import com.devpilot.server.application.dto.ApplicationDeploymentResponse;
import com.devpilot.server.application.dto.ApplicationResponse;
import com.devpilot.server.application.dto.CreateApplicationRequest;
import com.devpilot.server.application.dto.CreateDeploymentRecordRequest;
import com.devpilot.server.application.dto.UpdateApplicationRequest;
import com.devpilot.server.application.entity.ApplicationDeploymentEntity;
import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationDeploymentMapper;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.cicd.mapper.CicdPreviewMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.dto.ServerNodeResponse;
import com.devpilot.server.node.service.ServerNodeService;
import com.devpilot.server.security.DevPilotPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationService {

    private static final int HEALTH_INTERVAL_SECONDS = 30;
    private static final int HEALTH_CLAIM_TIMEOUT_SECONDS = 20;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final ApplicationMapper applicationMapper;
    private final ApplicationDeploymentMapper deploymentMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final ServerNodeService serverNodeService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final CicdPreviewMapper previewMapper;

    public List<ApplicationResponse> list() {
        return applicationMapper.selectAll().stream().map(this::toResponse).toList();
    }

    public ApplicationResponse get(Long id) {
        return toResponse(require(id));
    }

    @Transactional
    public ApplicationResponse create(CreateApplicationRequest request, DevPilotPrincipal principal) {
        String code = request.code().trim().toLowerCase(Locale.ROOT);
        if (applicationMapper.selectByCode(code) != null) {
            throw BusinessException.conflict(40921, "应用编码已存在");
        }
        DockerContainerSnapshotEntity container = validateBinding(request.serverId(), request.containerSnapshotId());
        validateUrl(request.healthCheckUrl(), "健康检查 URL");
        validateUrl(request.accessUrl(), "访问 URL");
        LocalDateTime now = now();
        ApplicationEntity entity = new ApplicationEntity();
        entity.setName(request.name().trim());
        entity.setCode(code);
        entity.setDescription(trimToNull(request.description()));
        entity.setEnvironment(request.environment());
        entity.setServerId(request.serverId());
        entity.setDeployType("DOCKER");
        entity.setContainerSnapshotId(container.getId());
        entity.setCurrentVersion(trimToNull(request.currentVersion()));
        entity.setHealthCheckUrl(trimToNull(request.healthCheckUrl()));
        entity.setAccessUrl(trimToNull(request.accessUrl()));
        entity.setStatus(runtimeStatus(container));
        entity.setHealthStatus("UNKNOWN");
        entity.setCreatedBy(principal.userId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        applicationMapper.insert(entity);
        return toResponse(entity);
    }

    @Transactional
    public ApplicationResponse update(Long id, UpdateApplicationRequest request) {
        ApplicationEntity entity = require(id);
        DockerContainerSnapshotEntity container = validateBinding(request.serverId(), request.containerSnapshotId());
        validateUrl(request.healthCheckUrl(), "健康检查 URL");
        validateUrl(request.accessUrl(), "访问 URL");
        boolean healthTargetChanged = !same(entity.getHealthCheckUrl(), trimToNull(request.healthCheckUrl()));
        entity.setName(request.name().trim());
        entity.setDescription(trimToNull(request.description()));
        entity.setEnvironment(request.environment());
        entity.setServerId(request.serverId());
        entity.setContainerSnapshotId(container.getId());
        entity.setCurrentVersion(trimToNull(request.currentVersion()));
        entity.setHealthCheckUrl(trimToNull(request.healthCheckUrl()));
        entity.setAccessUrl(trimToNull(request.accessUrl()));
        entity.setStatus(runtimeStatus(container));
        if (healthTargetChanged) {
            entity.setHealthStatus("UNKNOWN");
            entity.setHealthMessage(null);
            entity.setHealthCheckedAt(null);
            entity.setHealthCheckClaimedAt(null);
        }
        entity.setUpdatedAt(now());
        applicationMapper.updateById(entity);
        return toResponse(entity);
    }

    @Transactional
    public void delete(Long id) {
        ApplicationEntity application = applicationMapper.selectByIdForUpdate(id);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
        if (previewMapper.countActive(id) > 0) {
            throw BusinessException.conflict(40956, "应用仍有活动 Preview，请先在 CI/CD 发布中心完成回收");
        }
        applicationMapper.deleteById(application.getId());
    }

    public List<ApplicationDeploymentResponse> deployments(Long applicationId) {
        require(applicationId);
        return deploymentMapper.selectByApplication(applicationId).stream().map(this::toDeploymentResponse).toList();
    }

    @Transactional
    public ApplicationDeploymentResponse recordDeployment(Long applicationId, CreateDeploymentRecordRequest request,
                                                           DevPilotPrincipal principal) {
        ApplicationEntity application = require(applicationId);
        LocalDateTime deployedAt = now();
        ApplicationDeploymentEntity deployment = new ApplicationDeploymentEntity();
        deployment.setApplicationId(applicationId);
        deployment.setVersion(request.version().trim());
        deployment.setServerId(application.getServerId());
        deployment.setDockerImage(request.dockerImage().trim());
        deployment.setOperatorId(principal.userId());
        deployment.setDeployedAt(deployedAt);
        deployment.setResult(request.result());
        deployment.setLogs(trimToNull(request.logs()));
        deploymentMapper.insert(deployment);
        if ("SUCCESS".equals(request.result())) {
            application.setCurrentVersion(request.version().trim());
            application.setLastDeployedAt(deployedAt);
            application.setUpdatedAt(deployedAt);
            applicationMapper.updateById(application);
        }
        return toDeploymentResponse(deployment);
    }

    @Transactional
    public AgentHealthTaskResponse claimHealthCheck(Long serverId) {
        LocalDateTime current = now();
        LocalDateTime claimBefore = current.minusSeconds(HEALTH_CLAIM_TIMEOUT_SECONDS);
        LocalDateTime checkBefore = current.minusSeconds(HEALTH_INTERVAL_SECONDS);
        for (int attempt = 0; attempt < 3; attempt++) {
            ApplicationEntity due = applicationMapper.selectDueHealthCheck(serverId, claimBefore, checkBefore);
            if (due == null) {
                return null;
            }
            if (applicationMapper.claimHealthCheck(due.getId(), current, claimBefore) == 1) {
                return new AgentHealthTaskResponse(due.getId(), due.getHealthCheckUrl(), 5);
            }
        }
        return null;
    }

    @Transactional
    public void recordHealthResult(Long serverId, Long applicationId, AgentHealthResultRequest request) {
        ApplicationEntity entity = require(applicationId);
        if (!entity.getServerId().equals(serverId)) {
            throw BusinessException.notFound(40421, "健康检查任务不存在");
        }
        String detail = healthDetail(request);
        entity.setHealthStatus(request.status());
        entity.setHealthMessage(detail);
        entity.setHealthCheckedAt(now());
        entity.setHealthCheckClaimedAt(null);
        entity.setUpdatedAt(now());
        applicationMapper.updateById(entity);
    }

    public long count() {
        return applicationMapper.countAll();
    }

    public long countUnhealthy() {
        return applicationMapper.countUnhealthy();
    }

    public long countDeploymentsToday() {
        return deploymentMapper.countSince(LocalDate.now(ZoneOffset.UTC).atStartOfDay());
    }

    public List<ApplicationResponse> serviceStatuses() {
        return applicationMapper.selectAll().stream().limit(6).map(this::toResponse).toList();
    }

    public List<ApplicationDeploymentResponse> recentDeployments() {
        return deploymentMapper.selectRecent(6).stream().map(this::toDeploymentResponse).toList();
    }

    private ApplicationEntity require(Long id) {
        ApplicationEntity entity = applicationMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.notFound(40420, "应用不存在");
        }
        return entity;
    }

    private DockerContainerSnapshotEntity validateBinding(Long serverId, Long containerSnapshotId) {
        serverNodeService.get(serverId);
        DockerContainerSnapshotEntity container = containerMapper.selectActiveById(containerSnapshotId);
        if (container == null || !container.getServerId().equals(serverId)) {
            throw BusinessException.badRequest(40021, "关联容器必须存在于所选服务器");
        }
        return container;
    }

    private ApplicationResponse toResponse(ApplicationEntity entity) {
        ServerNodeResponse server = serverNodeService.get(entity.getServerId());
        DockerContainerSnapshotEntity container = entity.getContainerSnapshotId() == null
                ? null : containerMapper.selectById(entity.getContainerSnapshotId());
        String status = runtimeStatus(container);
        if (!status.equals(entity.getStatus())) {
            entity.setStatus(status);
            entity.setUpdatedAt(now());
            applicationMapper.updateById(entity);
        }
        return new ApplicationResponse(entity.getId(), entity.getName(), entity.getCode(), entity.getDescription(),
                entity.getEnvironment(), entity.getServerId(), server.name(), entity.getDeployType(),
                entity.getContainerSnapshotId(), container == null ? null : container.getContainerId(),
                container == null ? null : container.getName(), container == null ? null : container.getImage(),
                container == null ? null : container.getIpAddress(),
                container == null ? List.of() : fromJson(container.getPortsJson()),
                entity.getCurrentVersion(), entity.getAccessUrl(), entity.getHealthCheckUrl(), status,
                entity.getHealthStatus(), entity.getHealthMessage(), entity.getHealthCheckedAt(),
                container == null ? null : container.getCpuUsage(),
                container == null ? null : container.getMemoryUsage(),
                container == null ? null : container.getMemoryLimit(), entity.getLastDeployedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private ApplicationDeploymentResponse toDeploymentResponse(ApplicationDeploymentEntity entity) {
        ApplicationEntity application = applicationMapper.selectById(entity.getApplicationId());
        ServerNodeResponse server = serverNodeService.get(entity.getServerId());
        UserEntity operator = userMapper.selectActiveById(entity.getOperatorId());
        return new ApplicationDeploymentResponse(entity.getId(), entity.getApplicationId(),
                application == null ? "Deleted application" : application.getName(), entity.getVersion(),
                entity.getServerId(), server.name(), entity.getDockerImage(), entity.getOperatorId(),
                operator == null ? "Unknown user" : operator.getDisplayName(), entity.getDeployedAt(),
                entity.getResult(), entity.getLogs());
    }

    private static String runtimeStatus(DockerContainerSnapshotEntity container) {
        if (container == null || container.getActive() == 0) {
            return "OFFLINE";
        }
        if ("running".equalsIgnoreCase(container.getState())) {
            return "unhealthy".equalsIgnoreCase(container.getHealth()) ? "WARNING" : "RUNNING";
        }
        return "dead".equalsIgnoreCase(container.getState()) ? "ERROR" : "OFFLINE";
    }

    private List<String> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static void validateUrl(String value, String label) {
        String candidate = trimToNull(value);
        if (candidate == null) {
            return;
        }
        try {
            URI uri = new URI(candidate);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw BusinessException.badRequest(40022, label + " 必须是 HTTP(S) URL");
            }
        } catch (URISyntaxException exception) {
            throw BusinessException.badRequest(40022, label + " 格式无效");
        }
    }

    private static String healthDetail(AgentHealthResultRequest request) {
        StringBuilder detail = new StringBuilder();
        if (request.httpStatus() != null) {
            detail.append("HTTP ").append(request.httpStatus()).append(" · ");
        }
        detail.append(request.latencyMillis()).append(" ms");
        if (request.message() != null && !request.message().isBlank()) {
            detail.append(" · ").append(request.message().trim());
        }
        return detail.substring(0, Math.min(detail.length(), 500));
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

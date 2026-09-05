package com.devpilot.server.docker.service;

import com.devpilot.server.agent.dto.AgentHeartbeatRequest;
import com.devpilot.server.agent.dto.AgentHeartbeatResponse;
import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.docker.dto.AgentDockerContainerSnapshot;
import com.devpilot.server.docker.dto.AgentDockerSnapshotRequest;
import com.devpilot.server.docker.dto.DockerContainerResponse;
import com.devpilot.server.docker.dto.DockerOverviewResponse;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import com.devpilot.server.docker.entity.DockerHostSnapshotEntity;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.docker.mapper.DockerHostSnapshotMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.service.ServerNodeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DockerInventoryService {

    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "PASSWORD", "PASSWD", "SECRET", "TOKEN", "PRIVATE_KEY", "CREDENTIAL", "AUTH", "COOKIE");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final AgentRegistrationService registrationService;
    private final ServerNodeService serverNodeService;
    private final DockerHostSnapshotMapper hostMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final ObjectMapper objectMapper;
    private final com.devpilot.server.application.mapper.ApplicationMapper applicationMapper;

    @Transactional
    public Long ingest(String rawToken, AgentDockerSnapshotRequest request) {
        AgentHeartbeatResponse heartbeat = registrationService.heartbeat(rawToken,
                new AgentHeartbeatRequest(request.agentVersion()));
        Long serverId = heartbeat.serverId();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime collectedAt = LocalDateTime.ofInstant(request.collectedAt(), ZoneOffset.UTC);
        DockerHostSnapshotEntity host = hostMapper.selectById(serverId);
        boolean insert = host == null;
        if (host == null) {
            host = new DockerHostSnapshotEntity();
            host.setServerId(serverId);
        }
        host.setAvailable(request.available() ? 1 : 0);
        host.setEngineVersion(trimToNull(request.engineVersion()));
        host.setErrorMessage(trimToNull(request.errorMessage()));
        host.setImageCount(request.images());
        host.setVolumeCount(request.volumes());
        host.setNetworkCount(request.networks());
        host.setCollectedAt(collectedAt);
        host.setUpdatedAt(now);
        if (insert) {
            hostMapper.insert(host);
        } else {
            hostMapper.updateById(host);
        }
        if (!request.available()) {
            return serverId;
        }

        containerMapper.markInactive(serverId, now);
        for (AgentDockerContainerSnapshot snapshot : request.containers()) {
            upsert(serverId, snapshot, now);
        }
        rebindApplications(serverId, now);
        return serverId;
    }

    private void rebindApplications(Long serverId, LocalDateTime timestamp) {
        List<DockerContainerSnapshotEntity> active = containerMapper.selectActive(serverId);
        for (var candidate : applicationMapper.selectByServer(serverId)) {
            var application = applicationMapper.selectByIdForUpdate(candidate.getId());
            if (!serverId.equals(application.getServerId())) continue;
            var previous = application.getContainerSnapshotId() == null ? null
                    : containerMapper.selectById(application.getContainerSnapshotId());
            String key = application.getRuntimeKey();
            if (key == null && previous != null) key = previous.getRuntimeKey();
            if (key == null && previous != null) key = "name:" + previous.getName();
            if (key == null) continue;
            String identity = key;
            var matches = active.stream().filter(c -> identity.equals(c.getRuntimeKey())
                    && "running".equalsIgnoreCase(c.getState())).toList();
            // During a rolling update two replicas can coexist. Do not guess which one serves traffic.
            if (matches.size() != 1) continue;
            var current = matches.getFirst();
            if (!current.getId().equals(application.getContainerSnapshotId()) || application.getRuntimeKey() == null) {
                application.setContainerSnapshotId(current.getId());
                application.setRuntimeKey(identity);
                application.setStatus("RUNNING");
                application.setUpdatedAt(timestamp);
                applicationMapper.updateById(application);
            }
        }
    }

    public DockerOverviewResponse overview(Long serverId) {
        if (serverId != null) {
            serverNodeService.get(serverId);
        }
        DockerHostSnapshotEntity host = serverId == null ? hostMapper.selectGlobalTotals() : hostMapper.selectById(serverId);
        long containers = serverId == null ? containerMapper.countAllActive() : containerMapper.countByServer(serverId);
        long running = serverId == null ? containerMapper.countRunning() : containerMapper.countRunningByServer(serverId);
        return new DockerOverviewResponse(serverId, host != null && host.getAvailable() == 1,
                host == null ? null : host.getEngineVersion(), host == null ? null : host.getErrorMessage(),
                containers, running, containers - running, host == null ? 0 : host.getImageCount(),
                host == null ? 0 : host.getVolumeCount(), host == null ? 0 : host.getNetworkCount(),
                host == null ? null : host.getCollectedAt());
    }

    public List<DockerContainerResponse> list(Long serverId) {
        if (serverId != null) {
            serverNodeService.get(serverId);
        }
        return containerMapper.selectActive(serverId).stream().map(this::toResponse).toList();
    }

    public DockerContainerResponse get(Long id) {
        return toResponse(requireContainer(id));
    }

    public DockerContainerSnapshotEntity requireContainer(Long id) {
        DockerContainerSnapshotEntity entity = containerMapper.selectActiveById(id);
        if (entity == null) {
            throw BusinessException.notFound(40411, "Docker 容器不存在");
        }
        return entity;
    }

    private void upsert(Long serverId, AgentDockerContainerSnapshot snapshot, LocalDateTime now) {
        DockerContainerSnapshotEntity entity = containerMapper.selectByDockerId(serverId, snapshot.containerId());
        boolean insert = entity == null;
        int previousRestartCount = entity == null || entity.getRestartCount() == null ? snapshot.restartCount()
                : entity.getRestartCount();
        if (entity == null) {
            entity = new DockerContainerSnapshotEntity();
            entity.setServerId(serverId);
            entity.setContainerId(snapshot.containerId());
            entity.setCreatedAt(now);
        }
        entity.setName(normalizeName(snapshot.name()));
        entity.setImage(snapshot.image());
        entity.setRuntimeKey(snapshot.runtimeKey() == null || snapshot.runtimeKey().isBlank()
                ? (snapshot.composeProject() != null && snapshot.composeService() != null
                    ? "compose:" + snapshot.composeProject() + ":" + snapshot.composeService()
                    : "name:" + normalizeName(snapshot.name())) : snapshot.runtimeKey());
        entity.setState(snapshot.state().toLowerCase(Locale.ROOT));
        entity.setStatus(trimToNull(snapshot.status()));
        entity.setHealth(trimToNull(snapshot.health()));
        entity.setCpuUsage(round(snapshot.cpuUsage()));
        entity.setMemoryUsage(snapshot.memoryUsage());
        entity.setMemoryLimit(snapshot.memoryLimit());
        entity.setNetworkRx(snapshot.networkRx());
        entity.setNetworkTx(snapshot.networkTx());
        entity.setIpAddress(trimToNull(snapshot.ipAddress()));
        entity.setPortsJson(json(snapshot.ports()));
        entity.setContainerCreatedAt(toUtc(snapshot.createdAt()));
        entity.setStartedAt(toUtc(snapshot.startedAt()));
        int restartDelta = Math.max(0, snapshot.restartCount() - previousRestartCount);
        if (insert || entity.getRestartWindowStartedAt() == null
                || entity.getRestartWindowStartedAt().isBefore(now.minusMinutes(10))) {
            entity.setRestartWindowStartedAt(now);
            entity.setRestartWindowCount(restartDelta);
        } else {
            entity.setRestartWindowCount(Math.max(0, entity.getRestartWindowCount() == null
                    ? restartDelta : entity.getRestartWindowCount() + restartDelta));
        }
        entity.setRestartCount(snapshot.restartCount());
        entity.setNetworkMode(trimToNull(snapshot.networkMode()));
        entity.setComposeProject(trimToNull(snapshot.composeProject()));
        entity.setComposeService(trimToNull(snapshot.composeService()));
        entity.setVolumesJson(json(snapshot.volumes()));
        entity.setEnvironmentJson(json(snapshot.environment().stream().map(DockerInventoryService::mask).toList()));
        entity.setActive(1);
        entity.setLastSeenAt(now);
        entity.setUpdatedAt(now);
        if (insert) {
            containerMapper.insert(entity);
        } else {
            containerMapper.updateById(entity);
        }
    }

    private DockerContainerResponse toResponse(DockerContainerSnapshotEntity entity) {
        String dockerId = entity.getContainerId();
        return new DockerContainerResponse(entity.getId(), entity.getServerId(), dockerId,
                dockerId.substring(0, Math.min(12, dockerId.length())), entity.getName(), entity.getImage(),
                entity.getState(), entity.getStatus(), entity.getHealth(), entity.getCpuUsage(),
                entity.getMemoryUsage(), entity.getMemoryLimit(), entity.getNetworkRx(), entity.getNetworkTx(),
                entity.getIpAddress(), fromJson(entity.getPortsJson()), entity.getContainerCreatedAt(),
                entity.getStartedAt(), entity.getRestartCount(), entity.getNetworkMode(),
                entity.getComposeProject(), entity.getComposeService(),
                fromJson(entity.getVolumesJson()), fromJson(entity.getEnvironmentJson()), entity.getLastSeenAt());
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode Docker snapshot", exception);
        }
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

    private static String mask(String entry) {
        int separator = entry.indexOf('=');
        String key = separator < 0 ? entry : entry.substring(0, separator);
        String normalized = key.toUpperCase(Locale.ROOT);
        boolean sensitive = SENSITIVE_MARKERS.stream().anyMatch(normalized::contains);
        return sensitive ? key + "=******" : entry;
    }

    private static String normalizeName(String name) {
        String normalized = name.trim();
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }

    private static LocalDateTime toUtc(java.time.Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) {
            throw BusinessException.badRequest(40001, "Docker 指标必须为有限数值");
        }
        return Math.round(value * 100.0) / 100.0;
    }
}

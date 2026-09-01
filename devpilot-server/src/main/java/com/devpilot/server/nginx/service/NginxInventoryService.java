package com.devpilot.server.nginx.service;

import com.devpilot.server.agent.dto.AgentHeartbeatRequest;
import com.devpilot.server.agent.dto.AgentHeartbeatResponse;
import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.nginx.dto.AgentNginxFileSnapshot;
import com.devpilot.server.nginx.dto.AgentNginxSnapshotRequest;
import com.devpilot.server.nginx.dto.NginxConfigResponse;
import com.devpilot.server.nginx.dto.NginxConfigSummaryResponse;
import com.devpilot.server.nginx.dto.NginxHostResponse;
import com.devpilot.server.nginx.entity.NginxConfigEntity;
import com.devpilot.server.nginx.entity.NginxHostSnapshotEntity;
import com.devpilot.server.nginx.mapper.NginxConfigMapper;
import com.devpilot.server.nginx.mapper.NginxHostSnapshotMapper;
import com.devpilot.server.node.dto.ServerNodeResponse;
import com.devpilot.server.node.service.ServerNodeService;
import com.devpilot.server.security.SecretHashing;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NginxInventoryService {

    private final AgentRegistrationService registrationService;
    private final ServerNodeService serverNodeService;
    private final NginxHostSnapshotMapper hostMapper;
    private final NginxConfigMapper configMapper;

    @Transactional
    public Long ingest(String rawToken, AgentNginxSnapshotRequest request) {
        AgentHeartbeatResponse heartbeat = registrationService.heartbeat(rawToken,
                new AgentHeartbeatRequest(request.agentVersion()));
        Long serverId = heartbeat.serverId();
        LocalDateTime current = now();
        NginxHostSnapshotEntity host = hostMapper.selectById(serverId);
        boolean insert = host == null;
        if (host == null) {
            host = new NginxHostSnapshotEntity();
            host.setServerId(serverId);
        }
        host.setEnabled(request.enabled() ? 1 : 0);
        host.setAvailable(request.available() ? 1 : 0);
        host.setNginxVersion(trimToNull(request.nginxVersion()));
        host.setConfigPath(trimToNull(request.configPath()));
        host.setErrorMessage(trimToNull(request.errorMessage()));
        host.setCollectedAt(LocalDateTime.ofInstant(request.collectedAt(), ZoneOffset.UTC));
        host.setUpdatedAt(current);
        if (insert) {
            hostMapper.insert(host);
        } else {
            hostMapper.updateById(host);
        }
        if (!request.enabled() || !request.available()) {
            return serverId;
        }
        for (AgentNginxFileSnapshot file : request.files()) {
            if (!SecretHashing.sha256(file.content()).equals(file.contentHash())) {
                throw BusinessException.badRequest(40031, "Nginx 配置哈希不匹配");
            }
        }
        configMapper.markInactive(serverId, current);
        for (AgentNginxFileSnapshot file : request.files()) {
            upsert(serverId, file, current);
        }
        return serverId;
    }

    public List<NginxHostResponse> hosts() {
        return hostMapper.selectList(null).stream().map(this::toHost).toList();
    }

    public NginxHostResponse host(Long serverId) {
        serverNodeService.get(serverId);
        NginxHostSnapshotEntity host = hostMapper.selectById(serverId);
        if (host == null) {
            return new NginxHostResponse(serverId, serverNodeService.get(serverId).name(), false, false,
                    null, null, "Agent 尚未上报 Nginx 状态", 0, null);
        }
        return toHost(host);
    }

    public List<NginxConfigSummaryResponse> list(Long serverId) {
        if (serverId != null) {
            serverNodeService.get(serverId);
        }
        return configMapper.selectActive(serverId).stream().map(this::toSummary).toList();
    }

    public NginxConfigResponse get(Long id) {
        NginxConfigEntity entity = require(id);
        ServerNodeResponse server = serverNodeService.get(entity.getServerId());
        return new NginxConfigResponse(entity.getId(), entity.getServerId(), server.name(), entity.getFilename(),
                entity.getContent(), entity.getContentHash(), entity.getLastSeenAt(), entity.getUpdatedAt());
    }

    public NginxConfigEntity require(Long id) {
        NginxConfigEntity entity = configMapper.selectActiveById(id);
        if (entity == null) {
            throw BusinessException.notFound(40431, "Nginx 配置不存在");
        }
        return entity;
    }

    private void upsert(Long serverId, AgentNginxFileSnapshot file, LocalDateTime current) {
        NginxConfigEntity entity = configMapper.selectByFilename(serverId, file.filename());
        boolean insert = entity == null;
        if (entity == null) {
            entity = new NginxConfigEntity();
            entity.setServerId(serverId);
            entity.setFilename(file.filename());
            entity.setCreatedAt(current);
        }
        entity.setContent(file.content());
        entity.setContentHash(file.contentHash());
        entity.setActive(1);
        entity.setLastSeenAt(current);
        entity.setUpdatedAt(current);
        if (insert) {
            configMapper.insert(entity);
        } else {
            configMapper.updateById(entity);
        }
    }

    private NginxHostResponse toHost(NginxHostSnapshotEntity entity) {
        ServerNodeResponse server = serverNodeService.get(entity.getServerId());
        return new NginxHostResponse(entity.getServerId(), server.name(), entity.getEnabled() == 1,
                entity.getAvailable() == 1, entity.getNginxVersion(), entity.getConfigPath(),
                entity.getErrorMessage(), configMapper.selectActive(entity.getServerId()).size(),
                entity.getCollectedAt());
    }

    private NginxConfigSummaryResponse toSummary(NginxConfigEntity entity) {
        return new NginxConfigSummaryResponse(entity.getId(), entity.getServerId(),
                serverNodeService.get(entity.getServerId()).name(), entity.getFilename(), entity.getContentHash(),
                entity.getContent().getBytes(StandardCharsets.UTF_8).length, entity.getLastSeenAt(),
                entity.getUpdatedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

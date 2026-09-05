package com.devpilot.server.agent.service;

import com.devpilot.server.agent.dto.AgentHeartbeatRequest;
import com.devpilot.server.agent.dto.AgentHeartbeatResponse;
import com.devpilot.server.agent.dto.AgentRegisterRequest;
import com.devpilot.server.agent.dto.AgentRegistrationResponse;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.entity.AgentTokenEntity;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.AgentTokenMapper;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.SecretHashing;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.devpilot.server.settings.service.SystemSettingsService;

@Service
@RequiredArgsConstructor
public class AgentRegistrationService {

    private final AgentTokenMapper agentTokenMapper;
    private final ServerNodeMapper serverNodeMapper;
    private final AgentProperties properties;
    private final SystemSettingsService settingsService;

    @Transactional
    public AgentRegistrationResponse register(AgentRegisterRequest request) {
        AgentTokenEntity token = requireToken(request.token(), false);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (serverNodeMapper.register(token.getServerId(), request, now) != 1
                || agentTokenMapper.activate(token.getId(), now) != 1) {
            throw BusinessException.unauthorized("Agent Token 已失效或服务器已被删除");
        }
        ServerNodeEntity node = serverNodeMapper.selectActiveById(token.getServerId());
        return new AgentRegistrationResponse(node.getId(), node.getName(),
                properties.heartbeatInterval().toSeconds(), settingsService.metricIntervalSeconds(), now);
    }

    @Transactional
    public AgentHeartbeatResponse heartbeat(String rawToken, AgentHeartbeatRequest request) {
        AgentTokenEntity token = requireToken(rawToken, true);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (serverNodeMapper.heartbeat(token.getServerId(), blankToNull(request.agentVersion()), now) != 1
                || agentTokenMapper.touchActive(token.getId(), now) != 1) {
            throw BusinessException.unauthorized("Agent Token 已失效或服务器已被删除");
        }
        if (request.listeningTcpPorts() != null) {
            String ports = request.listeningTcpPorts().stream().distinct().sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            serverNodeMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ServerNodeEntity>()
                    .eq("id", token.getServerId()).set("listening_tcp_ports", ports).set("ports_collected_at", now));
        }
        return new AgentHeartbeatResponse(token.getServerId(), "ONLINE",
                properties.heartbeatInterval().toSeconds(), settingsService.metricIntervalSeconds(), now);
    }

    @Scheduled(fixedDelayString = "${devpilot.agent.status-scan-interval:10s}")
    public void scanOfflineServers() {
        markOfflineNow();
    }

    public int markOfflineNow() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return serverNodeMapper.markOffline(now.minusSeconds(settingsService.heartbeatTimeoutSeconds()), now);
    }

    public Long authenticateActive(String rawToken) {
        return requireToken(rawToken, true).getServerId();
    }

    private AgentTokenEntity requireToken(String rawToken, boolean activeRequired) {
        if (rawToken == null || rawToken.isBlank()) {
            throw BusinessException.unauthorized("Agent Token 缺失");
        }
        AgentTokenEntity token = agentTokenMapper.selectByHash(SecretHashing.sha256(rawToken));
        boolean acceptedStatus = token != null && (activeRequired
                ? "ACTIVE".equals(token.getStatus())
                : ("PENDING".equals(token.getStatus()) || "ACTIVE".equals(token.getStatus())));
        if (!acceptedStatus || token.getRevokedAt() != null) {
            throw BusinessException.unauthorized("Agent Token 无效");
        }
        return token;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

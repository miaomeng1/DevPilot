package com.devpilot.server.nginx.service;

import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.audit.service.AuditLogService;
import com.devpilot.server.audit.service.AuditLogService.AuditRecord;
import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.nginx.dto.AgentNginxCommandResponse;
import com.devpilot.server.nginx.dto.AgentNginxCommandResultRequest;
import com.devpilot.server.nginx.dto.NginxCommandResponse;
import com.devpilot.server.nginx.dto.NginxConfigHistoryResponse;
import com.devpilot.server.nginx.entity.NginxCommandEntity;
import com.devpilot.server.nginx.entity.NginxConfigEntity;
import com.devpilot.server.nginx.entity.NginxConfigHistoryEntity;
import com.devpilot.server.nginx.entity.NginxHostSnapshotEntity;
import com.devpilot.server.nginx.mapper.NginxCommandMapper;
import com.devpilot.server.nginx.mapper.NginxConfigHistoryMapper;
import com.devpilot.server.nginx.mapper.NginxConfigMapper;
import com.devpilot.server.nginx.mapper.NginxHostSnapshotMapper;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SecretHashing;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NginxCommandService {

    private final NginxInventoryService inventoryService;
    private final NginxConfigMapper configMapper;
    private final NginxHostSnapshotMapper hostMapper;
    private final NginxCommandMapper commandMapper;
    private final NginxConfigHistoryMapper historyMapper;
    private final ServerNodeMapper serverMapper;
    private final UserMapper userMapper;
    private final AgentRegistrationService registrationService;
    private final AuditLogService auditLogService;

    @Transactional
    public NginxCommandResponse update(Long configId, String content, DevPilotPrincipal principal) {
        return enqueue(inventoryService.require(configId), content, "UPDATE", principal);
    }

    @Transactional
    public NginxCommandResponse rollback(Long configId, Long historyId, DevPilotPrincipal principal) {
        NginxConfigEntity config = inventoryService.require(configId);
        NginxConfigHistoryEntity target = historyMapper.selectById(historyId);
        if (target == null || !target.getConfigId().equals(configId) || !"SUCCEEDED".equals(target.getStatus())) {
            throw BusinessException.notFound(40433, "Nginx 历史版本不存在");
        }
        return enqueue(config, target.getOldContent(), "ROLLBACK", principal);
    }

    public NginxCommandResponse get(Long id) {
        NginxCommandEntity command = commandMapper.selectById(id);
        if (command == null) {
            throw BusinessException.notFound(40432, "Nginx 操作不存在");
        }
        return toResponse(command);
    }

    public List<NginxConfigHistoryResponse> history(Long configId) {
        inventoryService.require(configId);
        return historyMapper.selectByConfig(configId).stream().map(this::toHistoryResponse).toList();
    }

    @Transactional
    public AgentNginxCommandResponse claimNext(String rawToken) {
        Long serverId = registrationService.authenticateActive(rawToken);
        NginxCommandEntity command = commandMapper.selectNext(serverId);
        if (command == null) {
            return null;
        }
        LocalDateTime current = now();
        if (commandMapper.claim(command.getId(), current) != 1) {
            return null;
        }
        return new AgentNginxCommandResponse(command.getId(), command.getAction(), command.getFilename(),
                command.getDesiredContent());
    }

    @Transactional
    public void complete(String rawToken, Long commandId, AgentNginxCommandResultRequest request) {
        Long serverId = registrationService.authenticateActive(rawToken);
        NginxCommandEntity command = commandMapper.selectById(commandId);
        String status = request.status().toUpperCase(Locale.ROOT);
        if (command == null || !command.getServerId().equals(serverId)
                || !Set.of("REQUESTED", "CLAIMED").contains(command.getStatus())) {
            throw BusinessException.notFound(40432, "Nginx 操作不存在或已完成");
        }
        LocalDateTime current = now();
        command.setStatus(status);
        command.setValidationOutput(trimToNull(request.validationOutput()));
        command.setErrorMessage("FAILED".equals(status) ? failure(request.errorMessage()) : null);
        command.setCompletedAt(current);
        command.setUpdatedAt(current);
        commandMapper.updateById(command);
        NginxConfigHistoryEntity history = historyMapper.selectByCommand(commandId);
        history.setStatus(status);
        history.setErrorMessage(command.getErrorMessage());
        history.setCompletedAt(current);
        historyMapper.updateById(history);
        if ("SUCCEEDED".equals(status)) {
            NginxConfigEntity config = configMapper.selectById(command.getConfigId());
            config.setContent(command.getDesiredContent());
            config.setContentHash(SecretHashing.sha256(command.getDesiredContent()));
            config.setActive(1);
            config.setLastSeenAt(current);
            config.setUpdatedAt(current);
            configMapper.updateById(config);
        }
        auditResult(command, status, command.getErrorMessage());
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void failExpired() {
        LocalDateTime current = now();
        for (NginxCommandEntity command : commandMapper.selectExpired(current.minusMinutes(2))) {
            command.setStatus("FAILED");
            command.setErrorMessage("Agent 未在超时时间内完成 Nginx 操作");
            command.setCompletedAt(current);
            command.setUpdatedAt(current);
            commandMapper.updateById(command);
            NginxConfigHistoryEntity history = historyMapper.selectByCommand(command.getId());
            if (history != null) {
                history.setStatus("FAILED");
                history.setErrorMessage(command.getErrorMessage());
                history.setCompletedAt(current);
                historyMapper.updateById(history);
            }
            auditResult(command, "FAILED", command.getErrorMessage());
        }
    }

    private NginxCommandResponse enqueue(NginxConfigEntity config, String content, String action,
                                         DevPilotPrincipal principal) {
        if (content.equals(config.getContent())) {
            throw BusinessException.conflict(40931, "Nginx 配置内容没有变化");
        }
        ServerNodeEntity server = serverMapper.selectActiveById(config.getServerId());
        NginxHostSnapshotEntity host = hostMapper.selectById(config.getServerId());
        if (server == null || !"ONLINE".equals(server.getAgentStatus()) || host == null
                || host.getEnabled() != 1 || host.getAvailable() != 1) {
            throw BusinessException.conflict(40932, "Agent 或 Nginx 当前不可用");
        }
        if (commandMapper.countPending(config.getId()) > 0) {
            throw BusinessException.conflict(40933, "该配置已有待执行操作");
        }
        LocalDateTime current = now();
        NginxCommandEntity command = new NginxCommandEntity();
        command.setServerId(config.getServerId());
        command.setConfigId(config.getId());
        command.setFilename(config.getFilename());
        command.setAction(action);
        command.setDesiredContent(content);
        command.setStatus("REQUESTED");
        command.setRequestedBy(principal.userId());
        command.setRequestedAt(current);
        command.setUpdatedAt(current);
        commandMapper.insert(command);

        NginxConfigHistoryEntity history = new NginxConfigHistoryEntity();
        history.setConfigId(config.getId());
        history.setServerId(config.getServerId());
        history.setFilename(config.getFilename());
        history.setOldContent(config.getContent());
        history.setNewContent(content);
        history.setAction(action);
        history.setOperatorId(principal.userId());
        history.setCommandId(command.getId());
        history.setStatus("PENDING");
        history.setCreatedAt(current);
        historyMapper.insert(history);
        return toResponse(command);
    }

    private NginxConfigHistoryResponse toHistoryResponse(NginxConfigHistoryEntity entity) {
        UserEntity operator = userMapper.selectActiveById(entity.getOperatorId());
        return new NginxConfigHistoryResponse(entity.getId(), entity.getConfigId(), entity.getFilename(),
                entity.getOldContent(), entity.getNewContent(), entity.getAction(), entity.getOperatorId(),
                operator == null ? "Unknown user" : operator.getDisplayName(), entity.getCommandId(),
                entity.getStatus(), entity.getErrorMessage(), entity.getCreatedAt(), entity.getCompletedAt());
    }

    private static NginxCommandResponse toResponse(NginxCommandEntity entity) {
        return new NginxCommandResponse(entity.getId(), entity.getServerId(), entity.getConfigId(),
                entity.getFilename(), entity.getAction(), entity.getStatus(), entity.getValidationOutput(),
                entity.getErrorMessage(), entity.getRequestedAt(), entity.getCompletedAt());
    }

    private void auditResult(NginxCommandEntity command, String status, String error) {
        UserEntity user = userMapper.selectById(command.getRequestedBy());
        String desiredContent = command.getDesiredContent();
        String parameters = ("{\"phase\":\"EXECUTION\",\"commandId\":\"%s\","
                + "\"content\":\"[CONTENT %d bytes SHA-256 %s]\"}")
                .formatted(command.getId(), desiredContent.getBytes(StandardCharsets.UTF_8).length,
                        SecretHashing.sha256(desiredContent));
        auditLogService.record(new AuditRecord(command.getRequestedBy(),
                user == null ? null : user.getUsername(), command.getAction() + "_NGINX_RESULT", "NGINX_CONFIG",
                command.getConfigId().toString(), command.getFilename(), command.getServerId(), null, parameters,
                "SUCCEEDED".equals(status), error));
    }

    private static String failure(String value) {
        return value == null || value.isBlank() ? "Nginx 配置校验或 reload 失败" : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

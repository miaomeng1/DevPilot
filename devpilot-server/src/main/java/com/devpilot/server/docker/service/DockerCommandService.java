package com.devpilot.server.docker.service;

import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.audit.service.AuditLogService;
import com.devpilot.server.audit.service.AuditLogService.AuditRecord;
import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.docker.dto.AgentDockerCommandResponse;
import com.devpilot.server.docker.dto.AgentDockerCommandResultRequest;
import com.devpilot.server.docker.dto.DockerCommandResponse;
import com.devpilot.server.docker.entity.DockerCommandEntity;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import com.devpilot.server.docker.mapper.DockerCommandMapper;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DockerCommandService {

    private static final Set<String> ACTIONS = Set.of("START", "STOP", "RESTART", "REMOVE");
    private final DockerInventoryService inventoryService;
    private final DockerCommandMapper commandMapper;
    private final ServerNodeMapper serverNodeMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final UserMapper userMapper;
    private final AgentRegistrationService registrationService;
    private final LogRelayService logRelayService;
    private final AuditLogService auditLogService;

    @Transactional
    public DockerCommandResponse enqueue(Long snapshotId, String requestedAction,
                                         String removeConfirmation, DevPilotPrincipal principal) {
        String action = requestedAction.toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw BusinessException.badRequest(40011, "不支持的 Docker 操作");
        }
        DockerContainerSnapshotEntity container = inventoryService.requireContainer(snapshotId);
        ServerNodeEntity server = serverNodeMapper.selectActiveById(container.getServerId());
        if (server == null || !"ONLINE".equals(server.getAgentStatus())) {
            throw BusinessException.conflict(40911, "Agent 离线，无法执行 Docker 操作");
        }
        validateState(container, action, removeConfirmation);
        if (commandMapper.countPending(snapshotId) > 0) {
            throw BusinessException.conflict(40912, "该容器已有待执行操作");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DockerCommandEntity command = new DockerCommandEntity();
        command.setServerId(container.getServerId());
        command.setContainerSnapshotId(container.getId());
        command.setContainerId(container.getContainerId());
        command.setAction(action);
        command.setStatus("REQUESTED");
        command.setRequestedBy(principal.userId());
        command.setRequestedAt(now);
        command.setUpdatedAt(now);
        commandMapper.insert(command);
        return toResponse(command);
    }

    public DockerCommandResponse get(Long id) {
        DockerCommandEntity command = commandMapper.selectById(id);
        if (command == null) {
            throw BusinessException.notFound(40412, "Docker 操作不存在");
        }
        return toResponse(command);
    }

    @Transactional
    public AgentDockerCommandResponse claimNext(String rawToken) {
        Long serverId = registrationService.authenticateActive(rawToken);
        AgentDockerCommandResponse logTask = logRelayService.claim(serverId);
        if (logTask != null) {
            return logTask;
        }
        DockerCommandEntity command = commandMapper.selectNext(serverId);
        if (command == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (commandMapper.claim(command.getId(), serverId, now) != 1) {
            return null;
        }
        return new AgentDockerCommandResponse(command.getId(), command.getContainerId(), command.getAction(),
                null, null, null);
    }

    @Transactional
    public void complete(String rawToken, Long commandId, AgentDockerCommandResultRequest request) {
        Long serverId = registrationService.authenticateActive(rawToken);
        DockerCommandEntity command = commandMapper.selectById(commandId);
        String status = request.status().toUpperCase(Locale.ROOT);
        if (!Set.of("SUCCEEDED", "FAILED").contains(status)) {
            throw BusinessException.badRequest(40012, "操作结果仅支持 SUCCEEDED 或 FAILED");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String error = "FAILED".equals(status) ? trim(request.errorMessage()) : null;
        if (command == null || !serverId.equals(command.getServerId())
                || commandMapper.complete(commandId, serverId, status, error, now) != 1) {
            throw BusinessException.notFound(40412, "Docker 操作不存在或已完成");
        }
        auditResult(command, status, error);
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void failExpiredCommands() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String error = "Agent 执行超时";
        for (DockerCommandEntity command : commandMapper.selectExpired(now.minusMinutes(2))) {
            if (commandMapper.complete(command.getId(), command.getServerId(), "FAILED", error, now) == 1) {
                auditResult(command, "FAILED", error);
            }
        }
    }

    private static void validateState(DockerContainerSnapshotEntity container, String action,
                                      String removeConfirmation) {
        boolean running = "running".equalsIgnoreCase(container.getState());
        if ("START".equals(action) && running) {
            throw BusinessException.conflict(40913, "容器已在运行");
        }
        if (("STOP".equals(action) || "RESTART".equals(action)) && !running) {
            throw BusinessException.conflict(40913, "容器当前未运行");
        }
        if ("REMOVE".equals(action)) {
            if (running) {
                throw BusinessException.conflict(40914, "请先停止容器再删除");
            }
            if (removeConfirmation == null || !container.getName().equals(removeConfirmation.trim())) {
                throw BusinessException.badRequest(40013, "请输入容器名称以确认删除");
            }
        }
    }

    private static DockerCommandResponse toResponse(DockerCommandEntity entity) {
        return new DockerCommandResponse(entity.getId(), entity.getServerId(), entity.getContainerSnapshotId(),
                entity.getAction(), entity.getStatus(), entity.getErrorMessage(), entity.getRequestedAt(),
                entity.getCompletedAt());
    }

    private void auditResult(DockerCommandEntity command, String status, String error) {
        UserEntity user = userMapper.selectById(command.getRequestedBy());
        DockerContainerSnapshotEntity container = containerMapper.selectById(command.getContainerSnapshotId());
        String parameters = "{\"phase\":\"EXECUTION\",\"commandId\":\"%s\",\"action\":\"%s\"}"
                .formatted(command.getId(), command.getAction());
        auditLogService.record(new AuditRecord(command.getRequestedBy(),
                user == null ? null : user.getUsername(), command.getAction() + "_CONTAINER_RESULT", "CONTAINER",
                command.getContainerSnapshotId().toString(), container == null ? null : container.getName(),
                command.getServerId(), null, parameters, "SUCCEEDED".equals(status), error));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? "Docker 操作失败" : value.trim();
    }
}

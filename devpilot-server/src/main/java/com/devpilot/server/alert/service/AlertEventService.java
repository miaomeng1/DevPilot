package com.devpilot.server.alert.service;

import com.devpilot.server.alert.dto.AlertEventResponse;
import com.devpilot.server.alert.dto.AlertDeliveryResponse;
import com.devpilot.server.alert.dto.AlertSummaryResponse;
import com.devpilot.server.alert.entity.AlertEventEntity;
import com.devpilot.server.alert.entity.AlertNotificationEntity;
import com.devpilot.server.alert.entity.AlertRuleEntity;
import com.devpilot.server.alert.mapper.AlertEventMapper;
import com.devpilot.server.alert.mapper.AlertNotificationMapper;
import com.devpilot.server.alert.mapper.AlertRuleMapper;
import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertEventService {

    private final AlertEventMapper eventMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertNotificationMapper notificationMapper;
    private final ServerNodeMapper serverMapper;
    private final UserMapper userMapper;

    public List<AlertEventResponse> list(String status, String severity, Long serverId) {
        validateFilter(status, "FIRING", "ACKNOWLEDGED", "RESOLVED");
        validateFilter(severity, "INFO", "WARNING", "CRITICAL");
        return eventMapper.selectFiltered(blankToNull(status), blankToNull(severity), serverId)
                .stream().map(this::toResponse).toList();
    }

    public List<AlertEventResponse> current(int limit) {
        return eventMapper.selectCurrent(Math.max(1, Math.min(limit, 20))).stream().map(this::toResponse).toList();
    }

    public AlertSummaryResponse summary() {
        return new AlertSummaryResponse(eventMapper.countActive(), eventMapper.countActiveCritical());
    }

    @Transactional
    public AlertEventResponse acknowledge(Long id, DevPilotPrincipal principal) {
        AlertEventEntity event = require(id);
        if ("RESOLVED".equals(event.getStatus())) {
            throw BusinessException.conflict(40930, "已恢复的告警不能确认");
        }
        if ("FIRING".equals(event.getStatus())) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            event.setStatus("ACKNOWLEDGED");
            event.setAcknowledgedBy(principal.userId());
            event.setAcknowledgedAt(now);
            event.setUpdatedAt(now);
            eventMapper.updateById(event);
        }
        return toResponse(event);
    }

    private AlertEventEntity require(Long id) {
        AlertEventEntity event = eventMapper.selectById(id);
        if (event == null) {
            throw BusinessException.notFound(40431, "告警事件不存在");
        }
        return event;
    }

    private AlertEventResponse toResponse(AlertEventEntity event) {
        AlertRuleEntity rule = ruleMapper.selectById(event.getRuleId());
        ServerNodeEntity server = serverMapper.selectById(event.getServerId());
        UserEntity user = event.getAcknowledgedBy() == null ? null : userMapper.selectActiveById(event.getAcknowledgedBy());
        List<AlertNotificationEntity> notifications = notificationMapper.selectByEvent(event.getId());
        String notificationStatus = notificationStatus(notifications);
        return new AlertEventResponse(event.getId().toString(), event.getRuleId().toString(),
                rule == null ? "Deleted rule" : rule.getName(), rule == null ? null : rule.getMetricType(),
                event.getServerId().toString(), server == null ? "Deleted server" : server.getName(),
                event.getResourceType(), event.getResourceId(), event.getResourceName(), event.getSeverity(),
                event.getMessage(), event.getStatus(), event.getCurrentValue(),
                rule == null ? null : rule.getThreshold(), rule == null ? null : rule.getOperator(),
                event.getStartedAt(), event.getAcknowledgedBy() == null ? null : event.getAcknowledgedBy().toString(),
                user == null ? null : user.getDisplayName(), event.getAcknowledgedAt(), event.getResolvedAt(),
                event.getUpdatedAt(), notificationStatus, notifications.stream().map(this::toDelivery).toList());
    }

    private AlertDeliveryResponse toDelivery(AlertNotificationEntity notification) {
        return new AlertDeliveryResponse(notification.getId(),
                notification.getRouteName() == null ? "兼容 Webhook" : notification.getRouteName(),
                notification.getTransitionType(), notification.getStatus(), notification.getAttemptCount(),
                notification.getResponseCode(), notification.getErrorMessage(), notification.getSentAt(),
                notification.getUpdatedAt());
    }

    private static String notificationStatus(List<AlertNotificationEntity> notifications) {
        if (notifications.isEmpty()) return "NONE";
        String transition = notifications.getFirst().getTransitionType();
        List<AlertNotificationEntity> latest = notifications.stream()
                .filter(notification -> transition.equals(notification.getTransitionType())).toList();
        boolean succeeded = latest.stream().anyMatch(notification -> "SUCCEEDED".equals(notification.getStatus()));
        boolean failed = latest.stream().anyMatch(notification -> "FAILED".equals(notification.getStatus()));
        if (failed && succeeded) return "PARTIAL";
        if (failed) return "FAILED";
        if (latest.stream().anyMatch(notification -> "PENDING".equals(notification.getStatus()))) return "PENDING";
        if (succeeded) return "SUCCEEDED";
        if (latest.stream().anyMatch(notification -> "MUTED".equals(notification.getStatus()))) return "MUTED";
        return latest.getFirst().getStatus();
    }

    private static void validateFilter(String value, String... allowed) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (java.util.Arrays.stream(allowed).noneMatch(value::equals)) {
            throw BusinessException.badRequest(40034, "告警筛选条件无效");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

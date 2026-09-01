package com.devpilot.server.alert.service;

import com.devpilot.server.alert.dto.AlertRuleRequest;
import com.devpilot.server.alert.dto.AlertRuleResponse;
import com.devpilot.server.alert.entity.AlertRuleEntity;
import com.devpilot.server.alert.mapper.AlertRuleMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private static final Set<String> PERCENTAGE_METRICS = Set.of("SERVER_CPU", "SERVER_MEMORY", "SERVER_DISK");
    private final AlertRuleMapper ruleMapper;
    private final ServerNodeMapper serverMapper;
    private final AlertEvaluationService evaluationService;

    public List<AlertRuleResponse> list() {
        return ruleMapper.selectAllActive().stream().map(this::toResponse).toList();
    }

    public AlertRuleResponse get(Long id) {
        return toResponse(require(id));
    }

    @Transactional
    public AlertRuleResponse create(AlertRuleRequest request, DevPilotPrincipal principal) {
        validate(request);
        LocalDateTime now = now();
        AlertRuleEntity entity = new AlertRuleEntity();
        apply(entity, request);
        entity.setDeleted(0);
        entity.setCreatedBy(principal.userId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        ruleMapper.insert(entity);
        return toResponse(entity);
    }

    @Transactional
    public AlertRuleResponse update(Long id, AlertRuleRequest request) {
        validate(request);
        AlertRuleEntity entity = require(id);
        boolean reset = !entity.getMetricType().equals(request.metricType())
                || !java.util.Objects.equals(entity.getServerId(), request.serverId())
                || !entity.getOperator().equals(request.operator())
                || !java.util.Objects.equals(entity.getThreshold(), normalizedThreshold(request));
        apply(entity, request);
        entity.setUpdatedAt(now());
        ruleMapper.updateById(entity);
        if (reset || !request.enabled()) {
            evaluationService.resolveRule(id, now());
        }
        return toResponse(entity);
    }

    @Transactional
    public void delete(Long id) {
        AlertRuleEntity entity = require(id);
        evaluationService.resolveRule(id, now());
        ruleMapper.deleteById(entity.getId());
    }

    private void validate(AlertRuleRequest request) {
        if (request.serverId() != null && serverMapper.selectActiveById(request.serverId()) == null) {
            throw BusinessException.badRequest(40033, "告警规则关联的服务器不存在");
        }
        if (PERCENTAGE_METRICS.contains(request.metricType())) {
            if (request.threshold() == null || !Double.isFinite(request.threshold())
                    || request.threshold() < 0 || request.threshold() > 100) {
                throw BusinessException.badRequest(40033, "资源使用率阈值必须在 0 到 100 之间");
            }
        }
    }

    private static void apply(AlertRuleEntity entity, AlertRuleRequest request) {
        boolean percentage = PERCENTAGE_METRICS.contains(request.metricType());
        entity.setName(request.name().trim());
        entity.setMetricType(request.metricType());
        entity.setOperator(percentage ? request.operator() : "EQ");
        entity.setThreshold(normalizedThreshold(request));
        entity.setDurationSeconds(request.durationSeconds());
        entity.setSeverity(request.severity());
        entity.setServerId(request.serverId());
        entity.setEnabled(request.enabled() ? 1 : 0);
    }

    private static Double normalizedThreshold(AlertRuleRequest request) {
        return PERCENTAGE_METRICS.contains(request.metricType()) ? request.threshold() : 1.0;
    }

    private AlertRuleEntity require(Long id) {
        AlertRuleEntity entity = ruleMapper.selectActiveById(id);
        if (entity == null) {
            throw BusinessException.notFound(40430, "告警规则不存在");
        }
        return entity;
    }

    private AlertRuleResponse toResponse(AlertRuleEntity entity) {
        ServerNodeEntity server = entity.getServerId() == null ? null : serverMapper.selectActiveById(entity.getServerId());
        return new AlertRuleResponse(entity.getId().toString(), entity.getName(), entity.getMetricType(),
                entity.getOperator(), entity.getThreshold(), entity.getDurationSeconds(), entity.getSeverity(),
                entity.getServerId() == null ? null : entity.getServerId().toString(),
                server == null ? "All servers" : server.getName(), entity.getEnabled() == 1,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

package com.devpilot.server.alert.service;

import com.devpilot.server.alert.entity.AlertConditionStateEntity;
import com.devpilot.server.alert.entity.AlertEventEntity;
import com.devpilot.server.alert.entity.AlertNotificationEntity;
import com.devpilot.server.alert.entity.AlertRuleEntity;
import com.devpilot.server.alert.mapper.AlertConditionStateMapper;
import com.devpilot.server.alert.mapper.AlertEventMapper;
import com.devpilot.server.alert.mapper.AlertNotificationMapper;
import com.devpilot.server.alert.mapper.AlertRuleMapper;
import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.metric.entity.ServerMetricEntity;
import com.devpilot.server.metric.mapper.ServerMetricMapper;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluationService {

    private static final int METRIC_STALE_SECONDS = 120;
    private final AlertRuleMapper ruleMapper;
    private final AlertEventMapper eventMapper;
    private final AlertConditionStateMapper conditionMapper;
    private final AlertNotificationMapper notificationMapper;
    private final ServerNodeMapper serverMapper;
    private final ServerMetricMapper metricMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final ApplicationMapper applicationMapper;

    @Scheduled(fixedDelayString = "${devpilot.alert.evaluation-interval:10s}", initialDelayString = "${devpilot.alert.evaluation-initial-delay:10s}")
    @Transactional
    public void evaluateAll() {
        LocalDateTime now = now();
        for (AlertRuleEntity rule : ruleMapper.selectEnabled()) {
            try {
                evaluate(rule, now);
            } catch (RuntimeException exception) {
                log.error("Alert rule {} evaluation failed", rule.getId(), exception);
            }
        }
    }

    @Transactional
    public void resolveRule(Long ruleId, LocalDateTime now) {
        for (AlertConditionStateEntity state : conditionMapper.selectByRule(ruleId)) {
            conditionMapper.deleteById(state.getId());
        }
        for (AlertEventEntity event : eventMapper.selectActiveByRule(ruleId)) {
            resolveEvent(event, now);
        }
    }

    private void evaluate(AlertRuleEntity rule, LocalDateTime now) {
        List<Observation> observations = observations(rule, now);
        Set<String> observedKeys = new HashSet<>();
        for (Observation observation : observations) {
            observedKeys.add(observation.key());
            if (observation.conditionMet()) {
                recordTrue(rule, observation, now);
            } else {
                recordFalse(rule, observation.resourceType(), observation.resourceId(), now);
            }
        }
        for (AlertConditionStateEntity stale : conditionMapper.selectByRule(rule.getId())) {
            String key = stale.getResourceType() + ":" + stale.getResourceId();
            if (!observedKeys.contains(key)) {
                recordFalse(rule, stale.getResourceType(), stale.getResourceId(), now);
            }
        }
    }

    private List<Observation> observations(AlertRuleEntity rule, LocalDateTime now) {
        List<ServerNodeEntity> servers = serverMapper.selectAllActive().stream()
                .filter(server -> rule.getServerId() == null || rule.getServerId().equals(server.getId())).toList();
        List<Observation> result = new ArrayList<>();
        for (ServerNodeEntity server : servers) {
            switch (rule.getMetricType()) {
                case "SERVER_CPU", "SERVER_MEMORY", "SERVER_DISK" -> {
                    ServerMetricEntity metric = metricMapper.selectLatest(server.getId());
                    Double value = metricValue(rule.getMetricType(), metric, now);
                    result.add(new Observation(server.getId(), "SERVER", server.getId().toString(), server.getName(),
                            value, value != null && compare(value, rule.getOperator(), rule.getThreshold())));
                }
                case "AGENT_OFFLINE" -> result.add(new Observation(server.getId(), "SERVER",
                        server.getId().toString(), server.getName(), 1.0,
                        !"ONLINE".equals(server.getAgentStatus())));
                case "CONTAINER_STOPPED" -> {
                    for (DockerContainerSnapshotEntity container : containerMapper.selectActive(server.getId())) {
                        result.add(new Observation(server.getId(), "CONTAINER", container.getId().toString(),
                                container.getName(), "running".equalsIgnoreCase(container.getState()) ? 0.0 : 1.0,
                                !"running".equalsIgnoreCase(container.getState())));
                    }
                }
                case "APP_UNHEALTHY" -> {
                    for (ApplicationEntity application : applicationMapper.selectByServer(server.getId())) {
                        boolean unhealthy = "UNHEALTHY".equals(application.getHealthStatus());
                        result.add(new Observation(server.getId(), "APPLICATION", application.getId().toString(),
                                application.getName(), unhealthy ? 1.0 : 0.0, unhealthy));
                    }
                }
                default -> log.warn("Unknown alert metric type {}", rule.getMetricType());
            }
        }
        return result;
    }

    private void recordTrue(AlertRuleEntity rule, Observation observation, LocalDateTime now) {
        AlertConditionStateEntity state = conditionMapper.selectResource(rule.getId(), observation.resourceType(),
                observation.resourceId());
        if (state == null) {
            state = new AlertConditionStateEntity();
            state.setRuleId(rule.getId());
            state.setServerId(observation.serverId());
            state.setResourceType(observation.resourceType());
            state.setResourceId(observation.resourceId());
            state.setResourceName(observation.resourceName());
            state.setCurrentValue(observation.value());
            state.setFirstMetAt(now);
            state.setLastObservedAt(now);
            conditionMapper.insert(state);
        } else {
            state.setResourceName(observation.resourceName());
            state.setCurrentValue(observation.value());
            state.setLastObservedAt(now);
            conditionMapper.updateById(state);
        }
        AlertEventEntity active = eventMapper.selectActiveForResource(rule.getId(), observation.resourceType(),
                observation.resourceId());
        if (active != null) {
            active.setCurrentValue(observation.value());
            active.setMessage(message(rule, observation));
            active.setUpdatedAt(now);
            eventMapper.updateById(active);
            return;
        }
        if (!now.isBefore(state.getFirstMetAt().plusSeconds(rule.getDurationSeconds()))) {
            AlertEventEntity event = new AlertEventEntity();
            event.setRuleId(rule.getId());
            event.setServerId(observation.serverId());
            event.setResourceType(observation.resourceType());
            event.setResourceId(observation.resourceId());
            event.setResourceName(observation.resourceName());
            event.setSeverity(rule.getSeverity());
            event.setMessage(message(rule, observation));
            event.setStatus("FIRING");
            event.setCurrentValue(observation.value());
            event.setStartedAt(state.getFirstMetAt());
            event.setUpdatedAt(now);
            eventMapper.insert(event);
            queueNotification(event.getId(), "FIRING", now);
        }
    }

    private void recordFalse(AlertRuleEntity rule, String resourceType, String resourceId, LocalDateTime now) {
        AlertConditionStateEntity state = conditionMapper.selectResource(rule.getId(), resourceType, resourceId);
        if (state != null) {
            conditionMapper.deleteById(state.getId());
        }
        AlertEventEntity active = eventMapper.selectActiveForResource(rule.getId(), resourceType, resourceId);
        if (active != null) {
            resolveEvent(active, now);
        }
    }

    private void resolveEvent(AlertEventEntity event, LocalDateTime now) {
        event.setStatus("RESOLVED");
        event.setResolvedAt(now);
        event.setUpdatedAt(now);
        eventMapper.updateById(event);
        queueNotification(event.getId(), "RESOLVED", now);
    }

    private void queueNotification(Long eventId, String transition, LocalDateTime now) {
        AlertNotificationEntity notification = new AlertNotificationEntity();
        notification.setEventId(eventId);
        notification.setTransitionType(transition);
        notification.setStatus("PENDING");
        notification.setAttemptCount(0);
        notification.setNextAttemptAt(now);
        notification.setCreatedAt(now);
        notification.setUpdatedAt(now);
        notificationMapper.insert(notification);
    }

    private static Double metricValue(String type, ServerMetricEntity metric, LocalDateTime now) {
        if (metric == null || metric.getCollectedAt().isBefore(now.minusSeconds(METRIC_STALE_SECONDS))) {
            return null;
        }
        return switch (type) {
            case "SERVER_CPU" -> metric.getCpuUsage();
            case "SERVER_MEMORY" -> percentage(metric.getMemoryUsed(), metric.getMemoryTotal());
            case "SERVER_DISK" -> percentage(metric.getDiskUsed(), metric.getDiskTotal());
            default -> null;
        };
    }

    private static boolean compare(double value, String operator, double threshold) {
        return switch (operator) {
            case "GT" -> value > threshold;
            case "GTE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LTE" -> value <= threshold;
            case "EQ" -> Double.compare(value, threshold) == 0;
            case "NE" -> Double.compare(value, threshold) != 0;
            default -> false;
        };
    }

    private static String message(AlertRuleEntity rule, Observation observation) {
        if (rule.getMetricType().startsWith("SERVER_")) {
            return "%s: %s is %.2f%% (%s %.2f%%)".formatted(rule.getName(), observation.resourceName(),
                    observation.value(), symbol(rule.getOperator()), rule.getThreshold());
        }
        return switch (rule.getMetricType()) {
            case "AGENT_OFFLINE" -> rule.getName() + ": Agent is offline on " + observation.resourceName();
            case "CONTAINER_STOPPED" -> rule.getName() + ": Container " + observation.resourceName() + " is stopped";
            case "APP_UNHEALTHY" -> rule.getName() + ": Application " + observation.resourceName() + " is unhealthy";
            default -> rule.getName() + ": condition met for " + observation.resourceName();
        };
    }

    private static String symbol(String operator) {
        return switch (operator) {
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            case "EQ" -> "=";
            case "NE" -> "!=";
            default -> operator.toUpperCase(Locale.ROOT);
        };
    }

    private static double percentage(long used, long total) {
        return total == 0 ? 0 : Math.round(used * 10000.0 / total) / 100.0;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record Observation(Long serverId, String resourceType, String resourceId,
                               String resourceName, Double value, boolean conditionMet) {
        String key() {
            return resourceType + ":" + resourceId;
        }
    }
}

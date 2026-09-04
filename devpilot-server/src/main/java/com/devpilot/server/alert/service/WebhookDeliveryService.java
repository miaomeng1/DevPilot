package com.devpilot.server.alert.service;

import com.devpilot.server.alert.entity.AlertEventEntity;
import com.devpilot.server.alert.entity.AlertNotificationEntity;
import com.devpilot.server.alert.entity.AlertRuleEntity;
import com.devpilot.server.alert.mapper.AlertEventMapper;
import com.devpilot.server.alert.mapper.AlertNotificationMapper;
import com.devpilot.server.alert.mapper.AlertRuleMapper;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final AlertNotificationMapper notificationMapper;
    private final AlertEventMapper eventMapper;
    private final AlertRuleMapper ruleMapper;
    private final ServerNodeMapper serverMapper;
    private final AlertSettingsService settingsService;
    private final AlertRoutingService routingService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${devpilot.alert.webhook-delivery-interval:5s}", initialDelayString = "${devpilot.alert.webhook-delivery-initial-delay:12s}")
    public void deliverPending() {
        LocalDateTime now = now();
        for (AlertNotificationEntity notification : notificationMapper.selectDue(now)) {
            deliver(notification, now);
        }
    }

    private void deliver(AlertNotificationEntity notification, LocalDateTime now) {
        String url = null;
        String destinationType;
        try {
            AlertEventEntity event = eventMapper.selectById(notification.getEventId());
            if (event == null) {
                complete(notification, "SKIPPED", null, "Alert event no longer exists", now);
                return;
            }
            if (notification.getRouteName() != null) {
                AlertRoutingService.DeliveryTarget target = routingService.deliveryTarget(
                        notification.getRouteId(), event, Instant.now());
                if (target.muted()) {
                    complete(notification, "MUTED", null, target.mutedReason(), now);
                    return;
                }
                url = target.url();
                destinationType = target.destinationType();
            } else {
                if (!settingsService.isEnabled()) {
                    complete(notification, "SKIPPED", null, null, now);
                    return;
                }
                url = settingsService.webhookUrl();
                if (url == null) {
                    complete(notification, "SKIPPED", null, "Webhook URL is not configured", now);
                    return;
                }
                destinationType = AlertSettingsService.destinationType(url);
            }
            String payload = payload(notification, destinationType);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "DevPilot/0.1 alert-webhook")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                complete(notification, "SUCCEEDED", response.statusCode(), null, now);
            } else {
                fail(notification, response.statusCode(), "Webhook returned HTTP " + response.statusCode(), now);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(notification, null, "Webhook delivery was interrupted", now);
        } catch (Exception exception) {
            String safeError = safeMessage(exception, url);
            log.warn("Alert webhook delivery {} failed: {}", notification.getId(), safeError);
            fail(notification, null, safeError, now);
        }
    }

    private String payload(AlertNotificationEntity notification, String destinationType) throws Exception {
        AlertEventEntity event = eventMapper.selectById(notification.getEventId());
        if (event == null) {
            throw new IllegalStateException("Alert event no longer exists");
        }
        AlertRuleEntity rule = ruleMapper.selectById(event.getRuleId());
        ServerNodeEntity server = serverMapper.selectById(event.getServerId());
        String title = "[DevPilot][%s][%s] %s".formatted(event.getSeverity(), notification.getTransitionType(),
                rule == null ? "Alert" : rule.getName());
        String text = title + "\n" + event.getMessage() + "\nServer: "
                + (server == null ? event.getServerId() : server.getName()) + "\nEvent: " + event.getId();
        ObjectNode root = objectMapper.createObjectNode();
        switch (destinationType) {
            case "DISCORD" -> root.put("content", truncate(text, 1900));
            case "FEISHU" -> {
                root.put("msg_type", "text");
                root.putObject("content").put("text", text);
            }
            case "WECHAT_WORK" -> {
                root.put("msgtype", "text");
                root.putObject("text").put("content", text);
            }
            default -> {
                root.put("type", "devpilot.alert." + notification.getTransitionType().toLowerCase());
                root.put("title", title);
                root.put("message", event.getMessage());
                root.put("transition", notification.getTransitionType());
                root.put("occurredAt", now().toInstant(ZoneOffset.UTC).toString());
                ObjectNode data = root.putObject("event");
                data.put("id", event.getId().toString());
                data.put("ruleId", event.getRuleId().toString());
                data.put("ruleName", rule == null ? "Deleted rule" : rule.getName());
                data.put("metricType", rule == null ? null : rule.getMetricType());
                data.put("serverId", event.getServerId().toString());
                data.put("serverName", server == null ? "Deleted server" : server.getName());
                data.put("resourceType", event.getResourceType());
                data.put("resourceId", event.getResourceId());
                data.put("resourceName", event.getResourceName());
                data.put("severity", event.getSeverity());
                data.put("status", event.getStatus());
                if (event.getCurrentValue() != null) {
                    data.put("currentValue", event.getCurrentValue());
                }
                data.put("startedAt", event.getStartedAt().toInstant(ZoneOffset.UTC).toString());
                if (event.getResolvedAt() != null) {
                    data.put("resolvedAt", event.getResolvedAt().toInstant(ZoneOffset.UTC).toString());
                }
            }
        }
        return objectMapper.writeValueAsString(root);
    }

    private void fail(AlertNotificationEntity notification, Integer code, String message, LocalDateTime now) {
        int attempts = notification.getAttemptCount() + 1;
        notification.setStatus("FAILED");
        notification.setAttemptCount(attempts);
        notification.setResponseCode(code);
        notification.setErrorMessage(truncate(message, 1000));
        notification.setNextAttemptAt(now.plusSeconds(Math.min(300, 5L << Math.min(attempts - 1, 6))));
        notification.setUpdatedAt(now);
        notificationMapper.updateById(notification);
    }

    private void complete(AlertNotificationEntity notification, String status, Integer code,
                          String message, LocalDateTime now) {
        notification.setStatus(status);
        notification.setAttemptCount(notification.getAttemptCount() + ("SUCCEEDED".equals(status) ? 1 : 0));
        notification.setResponseCode(code);
        notification.setErrorMessage(message);
        notification.setSentAt("SUCCEEDED".equals(status) ? now : null);
        notification.setUpdatedAt(now);
        notificationMapper.updateById(notification);
    }

    private static String safeMessage(Exception exception, String webhookUrl) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return exception.getClass().getSimpleName();
        if (webhookUrl != null && !webhookUrl.isBlank()) value = value.replace(webhookUrl, "[WEBHOOK]");
        return truncate(value, 1000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

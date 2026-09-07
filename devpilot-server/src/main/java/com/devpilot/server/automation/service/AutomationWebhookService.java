package com.devpilot.server.automation.service;

import com.devpilot.server.alert.entity.AlertEventEntity;
import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.automation.dto.AutomationDeliveryResponse;
import com.devpilot.server.automation.dto.AutomationWebhookRequest;
import com.devpilot.server.automation.dto.AutomationWebhookResponse;
import com.devpilot.server.automation.dto.CreatedAutomationWebhookResponse;
import com.devpilot.server.automation.entity.AutomationWebhookDeliveryEntity;
import com.devpilot.server.automation.entity.AutomationWebhookSubscriptionEntity;
import com.devpilot.server.automation.mapper.AutomationWebhookDeliveryMapper;
import com.devpilot.server.automation.mapper.AutomationWebhookSubscriptionMapper;
import com.devpilot.server.cicd.entity.CicdDeploymentEntity;
import com.devpilot.server.cicd.entity.CicdPipelineRunEntity;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AutomationWebhookService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED = Set.of("ALERT_FIRING", "ALERT_RESOLVED",
            "DEPLOYMENT_HEALTHY", "DEPLOYMENT_FAILED", "BUILD_FAILED", "ROLLBACK_HEALTHY", "ROLLBACK_FAILED");
    private final AutomationWebhookSubscriptionMapper subscriptionMapper;
    private final AutomationWebhookDeliveryMapper deliveryMapper;
    private final SensitiveSettingCipher cipher;
    private final ObjectMapper objectMapper;
    private final ApplicationMapper applicationMapper;

    public List<AutomationWebhookResponse> subscriptions() {
        return subscriptionMapper.selectAll().stream().map(AutomationWebhookService::response).toList();
    }

    public List<AutomationDeliveryResponse> deliveries() {
        return deliveryMapper.selectRecent().stream().map(AutomationWebhookService::response).toList();
    }

    @Transactional
    public CreatedAutomationWebhookResponse create(AutomationWebhookRequest request, DevPilotPrincipal principal) {
        URI endpoint = validatedEndpoint(request.endpointUrl());
        List<String> types = normalizedTypes(request.eventTypes());
        String name = request.name().trim();
        if (subscriptionMapper.selectByName(name) != null) {
            throw BusinessException.conflict(40972, "Webhook 订阅名称已存在");
        }
        String secret = secret();
        LocalDateTime now = now();
        AutomationWebhookSubscriptionEntity entity = new AutomationWebhookSubscriptionEntity();
        entity.setName(name);
        entity.setEndpointUrlEncrypted(cipher.encrypt(endpoint.toString()));
        entity.setEndpointHost(endpoint.getHost());
        entity.setSecretEncrypted(cipher.encrypt(secret));
        entity.setEventTypes(String.join(",", types));
        entity.setEnabled(1);
        entity.setCreatedBy(principal.userId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        subscriptionMapper.insert(entity);
        return new CreatedAutomationWebhookResponse(response(entity), secret);
    }

    @Transactional
    public AutomationWebhookResponse setEnabled(Long id, boolean enabled) {
        AutomationWebhookSubscriptionEntity entity = requireSubscription(id);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedAt(now());
        subscriptionMapper.updateById(entity);
        return response(entity);
    }

    @Transactional
    public void delete(Long id) {
        requireSubscription(id);
        subscriptionMapper.deleteById(id);
    }

    @Transactional
    public void retry(Long id) {
        AutomationWebhookDeliveryEntity delivery = deliveryMapper.selectById(id);
        if (delivery == null) throw BusinessException.notFound(40473, "Webhook 投递记录不存在");
        if (delivery.getSubscriptionId() == null || subscriptionMapper.selectById(delivery.getSubscriptionId()) == null) {
            throw BusinessException.conflict(40973, "订阅已删除，无法重发");
        }
        LocalDateTime timestamp = now();
        int changed = deliveryMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AutomationWebhookDeliveryEntity>()
                        .eq("id", id).eq("status", "FAILED").eq("attempt_count", delivery.getAttemptCount())
                        .set("status", "PENDING").set("attempt_count", 0).set("response_code", null)
                        .set("error_message", null).set("sent_at", null)
                        .set("next_attempt_at", timestamp).set("updated_at", timestamp));
        if (changed != 1) throw BusinessException.conflict(40973, "只能重试仍处于失败状态的投递；请刷新后核对，成功或已排队的事件不会再次重发");
    }

    @Transactional
    public void publishAlert(AlertEventEntity alert, String transition) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("alertId", alert.getId().toString());
        data.put("serverId", alert.getServerId().toString());
        data.put("resourceType", alert.getResourceType());
        data.put("resourceId", alert.getResourceId());
        data.put("resourceName", alert.getResourceName());
        data.put("severity", alert.getSeverity());
        data.put("status", alert.getStatus());
        data.put("message", alert.getMessage());
        data.put("reason", "RESOLVED".equals(transition)
                ? "告警已解除（RESOLVED）；规则变更也可能解除告警，请核对健康恢复证据" : alert.getMessage());
        data.put("detailsPath", "/alerts");
        if ("APPLICATION".equals(alert.getResourceType())) {
            Long applicationId = null;
            try {
                applicationId = Long.valueOf(alert.getResourceId());
            } catch (NumberFormatException ignored) {
                // Historical/missing resources still need a usable alert notification.
            }
            ApplicationEntity application = applicationId == null ? null : applicationMapper.selectById(applicationId);
            if (application != null && alert.getServerId().equals(application.getServerId())) {
                data.put("applicationId", application.getId().toString());
                data.put("applicationName", application.getName());
                data.put("environment", application.getEnvironment());
                // This is the saved application version, not fresh Agent/digest evidence.
                data.put("recordedVersion", application.getCurrentVersion());
                data.put("detailsPath", "/applications/" + application.getId());
            }
        }
        publish("ALERT_" + transition, "alert/" + alert.getId(), data);
    }

    @Transactional
    public void publishDeployment(CicdDeploymentEntity deployment, ApplicationEntity application, boolean healthy) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("deploymentId", deployment.getId().toString());
        data.put("applicationId", deployment.getApplicationId().toString());
        data.put("applicationName", application.getName());
        data.put("environment", application.getEnvironment());
        data.put("serverId", application.getServerId().toString());
        data.put("kind", deployment.getDeploymentKind());
        data.put("provider", deployment.getProvider());
        data.put("image", deployment.getImageUri());
        data.put("status", deployment.getStatus());
        data.put("detailsPath", "/cicd?application=" + application.getId());
        data.put("reason", healthy ? "健康验证通过" : "部署未通过，请查看部署记录与日志");
        publish(healthy ? "DEPLOYMENT_HEALTHY" : "DEPLOYMENT_FAILED",
                "deployment/" + deployment.getId(), data);
        // Keep existing deployment subscriptions compatible; rollback-specific subscriptions are opt-in.
        if ("ROLLBACK".equals(deployment.getDeploymentKind())) {
            publish(healthy ? "ROLLBACK_HEALTHY" : "ROLLBACK_FAILED", "deployment/" + deployment.getId(), data);
        }
    }

    @Transactional
    public void publishBuildFailure(CicdPipelineRunEntity run, ApplicationEntity application) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("pipelineRunId", run.getId().toString());
        data.put("externalRunId", run.getExternalRunId());
        data.put("applicationId", application.getId().toString());
        data.put("applicationName", application.getName());
        data.put("environment", application.getEnvironment());
        data.put("commit", run.getCommitSha());
        data.put("image", run.getImageUri());
        data.put("status", run.getStatus());
        data.put("testStatus", run.getTestStatus());
        data.put("securityStatus", run.getSecurityStatus());
        // Do not forward arbitrary CI summary/log text, which may contain credentials.
        data.put("reason", "构建未成功，请查看测试、安全扫描及构建任务结果");
        data.put("detailsPath", "/cicd?application=" + application.getId());
        publish("BUILD_FAILED", "build/" + run.getId(), data);
    }

    private void publish(String eventType, String subject, ObjectNode data) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime timestamp = now();
        ObjectNode cloudEvent = objectMapper.createObjectNode();
        cloudEvent.put("specversion", "1.0");
        cloudEvent.put("id", eventId);
        cloudEvent.put("source", "urn:devpilot:control-plane");
        cloudEvent.put("type", cloudEventType(eventType));
        cloudEvent.put("subject", subject);
        cloudEvent.put("time", timestamp.toInstant(ZoneOffset.UTC).toString());
        cloudEvent.put("datacontenttype", "application/json");
        cloudEvent.set("data", data);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(cloudEvent);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize automation event", exception);
        }
        for (AutomationWebhookSubscriptionEntity subscription : subscriptionMapper.selectEnabled()) {
            if (!Set.of(subscription.getEventTypes().split(",")).contains(eventType)) continue;
            AutomationWebhookDeliveryEntity delivery = new AutomationWebhookDeliveryEntity();
            delivery.setEventId(eventId);
            delivery.setSubscriptionId(subscription.getId());
            delivery.setSubscriptionName(subscription.getName());
            delivery.setEventType(eventType);
            delivery.setSubject(subject);
            delivery.setPayloadJson(payload);
            delivery.setStatus("PENDING");
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(timestamp);
            delivery.setCreatedAt(timestamp);
            delivery.setUpdatedAt(timestamp);
            deliveryMapper.insert(delivery);
        }
    }

    String endpoint(AutomationWebhookSubscriptionEntity subscription) {
        return cipher.decrypt(subscription.getEndpointUrlEncrypted());
    }

    String signingSecret(AutomationWebhookSubscriptionEntity subscription) {
        return cipher.decrypt(subscription.getSecretEncrypted());
    }

    private AutomationWebhookSubscriptionEntity requireSubscription(Long id) {
        AutomationWebhookSubscriptionEntity entity = subscriptionMapper.selectById(id);
        if (entity == null) throw BusinessException.notFound(40472, "Webhook 订阅不存在");
        return entity;
    }

    private static URI validatedEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest(40072, "Webhook URL 无效");
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme())))) {
            throw BusinessException.badRequest(40072, "Webhook 必须使用 HTTPS；仅 loopback 允许 HTTP");
        }
        return uri;
    }

    private static List<String> normalizedTypes(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>(values);
        if (result.isEmpty() || !ALLOWED.containsAll(result)) throw BusinessException.badRequest(40073, "事件类型无效");
        return List.copyOf(result);
    }

    private static String cloudEventType(String type) {
        return "dev.devpilot." + type.toLowerCase().replace('_', '.') + ".v1";
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "dpwhsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static AutomationWebhookResponse response(AutomationWebhookSubscriptionEntity value) {
        return new AutomationWebhookResponse(value.getId(), value.getName(), value.getEndpointHost(),
                List.of(value.getEventTypes().split(",")), value.getEnabled() == 1,
                value.getCreatedAt(), value.getUpdatedAt());
    }

    private static AutomationDeliveryResponse response(AutomationWebhookDeliveryEntity value) {
        return new AutomationDeliveryResponse(value.getId(), value.getEventId(), value.getSubscriptionName(),
                value.getEventType(), value.getSubject(), value.getStatus(), value.getAttemptCount(),
                value.getResponseCode(), value.getErrorMessage(), value.getSentAt(), value.getCreatedAt(), value.getUpdatedAt());
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}

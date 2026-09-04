package com.devpilot.server.automation.service;

import com.devpilot.server.automation.entity.AutomationWebhookDeliveryEntity;
import com.devpilot.server.automation.entity.AutomationWebhookSubscriptionEntity;
import com.devpilot.server.automation.mapper.AutomationWebhookDeliveryMapper;
import com.devpilot.server.automation.mapper.AutomationWebhookSubscriptionMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationWebhookDeliveryService {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final AutomationWebhookDeliveryMapper deliveryMapper;
    private final AutomationWebhookSubscriptionMapper subscriptionMapper;
    private final AutomationWebhookService webhookService;

    @Scheduled(fixedDelayString = "${devpilot.automation.webhook-delivery-interval:5s}",
            initialDelayString = "${devpilot.automation.webhook-delivery-initial-delay:15s}")
    public void deliverPending() {
        LocalDateTime now = now();
        for (AutomationWebhookDeliveryEntity delivery : deliveryMapper.selectDue(now)) deliver(delivery, now);
    }

    void deliver(AutomationWebhookDeliveryEntity delivery, LocalDateTime timestamp) {
        String endpoint = null;
        try {
            AutomationWebhookSubscriptionEntity subscription = delivery.getSubscriptionId() == null ? null
                    : subscriptionMapper.selectById(delivery.getSubscriptionId());
            if (subscription == null || subscription.getEnabled() != 1) {
                complete(delivery, "SKIPPED", null, "Subscription is unavailable or disabled", timestamp);
                return;
            }
            endpoint = webhookService.endpoint(subscription);
            String signature = "sha256=" + hmac(webhookService.signingSecret(subscription), delivery.getPayloadJson());
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/cloudevents+json; charset=utf-8")
                    .header("User-Agent", "DevPilot/1.0 automation-webhook")
                    .header("X-DevPilot-Event", delivery.getEventType())
                    .header("X-DevPilot-Delivery", delivery.getEventId())
                    .header("X-DevPilot-Signature-256", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayloadJson(), StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                complete(delivery, "SUCCEEDED", response.statusCode(), null, timestamp);
            } else {
                fail(delivery, response.statusCode(), "Webhook returned HTTP " + response.statusCode(), timestamp);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(delivery, null, "Webhook delivery was interrupted", timestamp);
        } catch (Exception exception) {
            String message = safeMessage(exception, endpoint);
            log.warn("Automation webhook delivery {} failed: {}", delivery.getId(), message);
            fail(delivery, null, message, timestamp);
        }
    }

    private void fail(AutomationWebhookDeliveryEntity delivery, Integer code, String message, LocalDateTime now) {
        int attempts = delivery.getAttemptCount() + 1;
        delivery.setStatus("FAILED");
        delivery.setAttemptCount(attempts);
        delivery.setResponseCode(code);
        delivery.setErrorMessage(truncate(message, 1000));
        delivery.setNextAttemptAt(now.plusSeconds(Math.min(300, 5L << Math.min(attempts - 1, 6))));
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
    }

    private void complete(AutomationWebhookDeliveryEntity delivery, String status, Integer code,
                          String message, LocalDateTime now) {
        delivery.setStatus(status);
        delivery.setAttemptCount(delivery.getAttemptCount() + ("SUCCEEDED".equals(status) ? 1 : 0));
        delivery.setResponseCode(code);
        delivery.setErrorMessage(message);
        delivery.setSentAt("SUCCEEDED".equals(status) ? now : null);
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
    }

    private static String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String safeMessage(Exception exception, String endpoint) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return exception.getClass().getSimpleName();
        return truncate(endpoint == null ? value : value.replace(endpoint, "[WEBHOOK]"), 1000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}

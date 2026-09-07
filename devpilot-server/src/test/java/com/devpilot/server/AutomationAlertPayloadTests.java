package com.devpilot.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.devpilot.server.alert.entity.AlertEventEntity;
import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.automation.entity.AutomationWebhookDeliveryEntity;
import com.devpilot.server.automation.entity.AutomationWebhookSubscriptionEntity;
import com.devpilot.server.automation.mapper.AutomationWebhookDeliveryMapper;
import com.devpilot.server.automation.mapper.AutomationWebhookSubscriptionMapper;
import com.devpilot.server.automation.service.AutomationWebhookService;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AutomationAlertPayloadTests {
    @Mock AutomationWebhookSubscriptionMapper subscriptions;
    @Mock AutomationWebhookDeliveryMapper deliveries;
    @Mock ApplicationMapper applications;
    @Mock SensitiveSettingCipher cipher;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks AutomationWebhookService service;

    @Test void applicationAlertIncludesContextWithoutClaimingRuntimeEvidence() throws Exception {
        ApplicationEntity app = new ApplicationEntity();
        app.setId(42L); app.setServerId(9L); app.setName("Demo");
        app.setEnvironment("PRODUCTION"); app.setCurrentVersion("v4");
        when(applications.selectById(42L)).thenReturn(app);
        JsonNode data = publish("APPLICATION", "42", "FIRING");
        assertEquals("42", data.path("applicationId").asText());
        assertEquals("Demo", data.path("applicationName").asText());
        assertEquals("PRODUCTION", data.path("environment").asText());
        assertEquals("v4", data.path("recordedVersion").asText());
        assertEquals("/applications/42", data.path("detailsPath").asText());
        assertFalse(data.has("runtimeImage"));
        assertFalse(data.has("healthCheckUrl"));
    }

    @Test void movedApplicationDoesNotLeakAnotherServersContext() throws Exception {
        ApplicationEntity app = new ApplicationEntity();
        app.setId(42L); app.setServerId(10L); app.setName("Different server");
        when(applications.selectById(42L)).thenReturn(app);
        JsonNode data = publish("APPLICATION", "42", "FIRING");
        assertFalse(data.has("applicationName"));
        assertEquals("/alerts", data.path("detailsPath").asText());
    }

    @Test void deletedApplicationKeepsAlertEntryWithoutInventedVersion() throws Exception {
        when(applications.selectById(42L)).thenReturn(null);
        JsonNode data = publish("APPLICATION", "42", "RESOLVED");
        assertEquals("/alerts", data.path("detailsPath").asText());
        assertFalse(data.has("recordedVersion"));
        assertFalse(data.has("applicationName"));
    }

    @Test void malformedHistoricalResourceStillProducesResolvableNotification() throws Exception {
        JsonNode data = publish("APPLICATION", "missing/id", "RESOLVED");
        assertEquals("/alerts", data.path("detailsPath").asText());
        assertTrue(data.path("reason").asText().contains("恢复"));
        verifyNoInteractions(applications);
    }

    @Test void agentRecoveryPreservesOriginalMessageButExplainsResolution() throws Exception {
        JsonNode data = publish("SERVER", "9", "RESOLVED");
        assertEquals("Original failure", data.path("message").asText());
        assertTrue(data.path("reason").asText().contains("恢复"));
        assertEquals("/alerts", data.path("detailsPath").asText());
        verifyNoInteractions(applications);
    }

    private JsonNode publish(String type, String resourceId, String transition) throws Exception {
        AutomationWebhookSubscriptionEntity sub = new AutomationWebhookSubscriptionEntity();
        sub.setId(1L); sub.setName("Fixture"); sub.setEventTypes("ALERT_FIRING,ALERT_RESOLVED");
        when(subscriptions.selectEnabled()).thenReturn(List.of(sub));
        AlertEventEntity alert = new AlertEventEntity();
        alert.setId(11L); alert.setServerId(9L); alert.setResourceType(type);
        alert.setResourceId(resourceId); alert.setResourceName("Resource");
        alert.setStatus(transition); alert.setMessage("Original failure");
        service.publishAlert(alert, transition);
        ArgumentCaptor<AutomationWebhookDeliveryEntity> capture = ArgumentCaptor.forClass(AutomationWebhookDeliveryEntity.class);
        verify(deliveries).insert(capture.capture());
        return objectMapper.readTree(capture.getValue().getPayloadJson()).path("data");
    }
}

package com.devpilot.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.automation.entity.AutomationWebhookDeliveryEntity;
import com.devpilot.server.automation.entity.AutomationWebhookSubscriptionEntity;
import com.devpilot.server.automation.mapper.AutomationWebhookDeliveryMapper;
import com.devpilot.server.automation.mapper.AutomationWebhookSubscriptionMapper;
import com.devpilot.server.automation.service.AutomationWebhookService;
import com.devpilot.server.cicd.entity.CicdDeploymentEntity;
import com.devpilot.server.cicd.entity.CicdPipelineRunEntity;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeploymentNotificationPayloadTests {
    @Mock AutomationWebhookSubscriptionMapper subscriptions;
    @Mock AutomationWebhookDeliveryMapper deliveries;
    @Mock ApplicationMapper applications;
    @Mock SensitiveSettingCipher cipher;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks AutomationWebhookService service;

    @ParameterizedTest
    @CsvSource({"FAILED,FAILED,SKIPPED,false", "FAILED,PASSED,FAILED,false", "CANCELLED,PASSED,PASSED,true"})
    void buildFailureIncludesCommitEvenWithoutImageAndNeverForwardsSummary(
            String status, String testStatus, String securityStatus, boolean hasImage) throws Exception {
        var sub = new AutomationWebhookSubscriptionEntity();
        sub.setId(1L); sub.setName("Build acceptance"); sub.setEventTypes("BUILD_FAILED");
        when(subscriptions.selectEnabled()).thenReturn(List.of(sub));
        var app = new ApplicationEntity();
        app.setId(42L); app.setName("Demo"); app.setEnvironment("PRODUCTION");
        var run = new CicdPipelineRunEntity();
        run.setId(81L); run.setApplicationId(42L); run.setExternalRunId("build:github-123-1");
        run.setCommitSha("b".repeat(40)); run.setStatus(status);
        run.setTestStatus(testStatus); run.setSecurityStatus(securityStatus);
        String image = hasImage ? "ghcr.io/example/demo@sha256:" + "a".repeat(64) : null;
        run.setImageUri(image); run.setSummary("private-build-token-never-forward");
        service.publishBuildFailure(run, app);
        var capture = ArgumentCaptor.forClass(AutomationWebhookDeliveryEntity.class);
        verify(deliveries).insert(capture.capture());
        var delivery = capture.getValue();
        var event = objectMapper.readTree(delivery.getPayloadJson());
        var data = event.path("data");
        assertEquals("BUILD_FAILED", delivery.getEventType());
        assertEquals("build/81", event.path("subject").asText());
        assertEquals("42", data.path("applicationId").asText());
        assertEquals("Demo", data.path("applicationName").asText());
        assertEquals("PRODUCTION", data.path("environment").asText());
        assertEquals("b".repeat(40), data.path("commit").asText());
        if (hasImage) assertEquals(image, data.path("image").asText());
        else assertTrue(data.path("image").isNull(), "No manufactured image for a failed build");
        assertEquals(status, data.path("status").asText());
        assertEquals(testStatus, data.path("testStatus").asText());
        assertEquals(securityStatus, data.path("securityStatus").asText());
        assertEquals("/cicd?application=42", data.path("detailsPath").asText());
        assertFalse(data.path("reason").asText().isBlank());
        assertFalse(delivery.getPayloadJson().contains("private-build-token-never-forward"));
    }

    @ParameterizedTest
    @CsvSource({"RELEASE,true,HEALTHY", "RELEASE,false,FAILED", "ROLLBACK,true,HEALTHY", "ROLLBACK,false,UNHEALTHY"})
    void terminalEventsCarryExactArtifactContextWithoutForwardingProviderLogs(String kind, boolean healthy, String status) throws Exception {
        var sub = new AutomationWebhookSubscriptionEntity();
        sub.setId(1L); sub.setName("Acceptance");
        sub.setEventTypes("DEPLOYMENT_HEALTHY,DEPLOYMENT_FAILED,ROLLBACK_HEALTHY,ROLLBACK_FAILED");
        when(subscriptions.selectEnabled()).thenReturn(List.of(sub));
        var app = new ApplicationEntity();
        app.setId(42L); app.setServerId(9L); app.setName("Demo"); app.setEnvironment("PRODUCTION");
        var deployment = new CicdDeploymentEntity();
        deployment.setId(81L); deployment.setApplicationId(42L); deployment.setDeploymentKind(kind);
        deployment.setProvider("DOKPLOY"); deployment.setStatus(status);
        String image = "ghcr.io/example/demo@sha256:" + "a".repeat(64);
        deployment.setImageUri(image);
        deployment.setLogs("Provider diagnostic containing private-token-never-forward");
        service.publishDeployment(deployment, app, healthy);
        var capture = ArgumentCaptor.forClass(AutomationWebhookDeliveryEntity.class);
        verify(deliveries, times(kind.equals("ROLLBACK") ? 2 : 1)).insert(capture.capture());
        String outcome = healthy ? "HEALTHY" : "FAILED";
        Set<String> expected = kind.equals("ROLLBACK")
                ? Set.of("DEPLOYMENT_" + outcome, "ROLLBACK_" + outcome) : Set.of("DEPLOYMENT_" + outcome);
        assertEquals(expected, capture.getAllValues().stream().map(AutomationWebhookDeliveryEntity::getEventType).collect(Collectors.toSet()));
        for (var delivery : capture.getAllValues()) {
            var event = objectMapper.readTree(delivery.getPayloadJson());
            var data = event.path("data");
            assertEquals("deployment/81", event.path("subject").asText());
            assertEquals("42", data.path("applicationId").asText());
            assertEquals("Demo", data.path("applicationName").asText());
            assertEquals("PRODUCTION", data.path("environment").asText());
            assertEquals("9", data.path("serverId").asText());
            assertEquals(image, data.path("image").asText());
            assertEquals(kind, data.path("kind").asText());
            assertEquals("DOKPLOY", data.path("provider").asText());
            assertEquals(status, data.path("status").asText());
            assertEquals("/cicd?application=42", data.path("detailsPath").asText());
            assertFalse(data.path("reason").asText().isBlank());
            assertFalse(delivery.getPayloadJson().contains("private-token-never-forward"));
        }
    }
}

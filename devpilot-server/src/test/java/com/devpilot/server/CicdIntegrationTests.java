package com.devpilot.server;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devpilot.server.cicd.service.DeploymentWebhookClient;
import com.devpilot.server.cicd.service.CicdDeploymentService;
import com.devpilot.server.cicd.service.CicdPreviewService;
import com.devpilot.server.cicd.service.DeploymentWebhookClient.DeploymentState;
import com.devpilot.server.cicd.service.DeploymentWebhookClient.EnvironmentVariable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CicdIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CicdDeploymentService cicdDeploymentService;
    @Autowired private CicdPreviewService cicdPreviewService;
    @MockitoBean private DeploymentWebhookClient deploymentWebhookClient;
    @MockitoBean private com.devpilot.server.cicd.onboarding.RepositoryOnboardingClient onboardingRepositories;
    @MockitoBean private com.devpilot.server.cicd.onboarding.ProviderOnboardingClient onboardingProviders;

    @BeforeEach
    void resetDatabase() {
        TestDatabaseReset.reset(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM application_environment_variable");
        jdbcTemplate.update("DELETE FROM application_environment_state");
        jdbcTemplate.update("DELETE FROM cicd_preview");
        jdbcTemplate.update("DELETE FROM cicd_deployment");
        jdbcTemplate.update("DELETE FROM cicd_pipeline_run");
        jdbcTemplate.update("DELETE FROM cicd_configuration");
        jdbcTemplate.update("DELETE FROM alert_notification");
        jdbcTemplate.update("DELETE FROM alert_condition_state");
        jdbcTemplate.update("DELETE FROM alert_event");
        jdbcTemplate.update("DELETE FROM alert_rule");
        jdbcTemplate.update("DELETE FROM system_setting");
        jdbcTemplate.update("DELETE FROM nginx_config_history");
        jdbcTemplate.update("DELETE FROM nginx_command");
        jdbcTemplate.update("DELETE FROM nginx_config");
        jdbcTemplate.update("DELETE FROM nginx_host_snapshot");
        jdbcTemplate.update("DELETE FROM application_deployment");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM docker_command");
        jdbcTemplate.update("DELETE FROM docker_container_snapshot");
        jdbcTemplate.update("DELETE FROM docker_host_snapshot");
        jdbcTemplate.update("DELETE FROM server_metric");
        jdbcTemplate.update("DELETE FROM agent_token");
        jdbcTemplate.update("DELETE FROM server_node");
        jdbcTemplate.update("DELETE FROM auth_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    @Test
    void environmentVariablesAreEncryptedMaskedAndRevisionProtected() throws Exception {
        Fixture fixture = createApplication();
        mockMvc.perform(get("/api/cicd/applications/{id}/environment", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision", is(0)))
                .andExpect(jsonPath("$.data.syncStatus", is("NOT_CONFIGURED")));

        mockMvc.perform(put("/api/cicd/applications/{id}/environment", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedRevision":0,"variables":[
                                  {"key":"PUBLIC_URL","value":"https://demo.example.com","secret":false,
                                   "description":"Public origin"},
                                  {"key":"API_KEY","value":"top-secret-value","secret":true,
                                   "description":"Provider token"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision", is(1)))
                .andExpect(jsonPath("$.data.syncStatus", is("DIRTY")))
                .andExpect(jsonPath("$.data.variables[0].key", is("API_KEY")))
                .andExpect(jsonPath("$.data.variables[0].value").isEmpty())
                .andExpect(jsonPath("$.data.variables[0].configured", is(true)))
                .andExpect(jsonPath("$.data.variables[1].value", is("https://demo.example.com")));

        jdbcTemplate.query("SELECT value_cipher FROM application_environment_variable", result -> {
            String encrypted = result.getString(1);
            org.junit.jupiter.api.Assertions.assertTrue(encrypted.startsWith("v1:"));
            org.junit.jupiter.api.Assertions.assertFalse(encrypted.contains("top-secret-value"));
            org.junit.jupiter.api.Assertions.assertFalse(encrypted.contains("https://demo.example.com"));
        });
        String environmentAudit = jdbcTemplate.queryForObject(
                "SELECT request_params FROM audit_log WHERE action = 'UPDATE_APPLICATION_ENVIRONMENT'",
                String.class);
        org.junit.jupiter.api.Assertions.assertFalse(environmentAudit.contains("top-secret-value"));
        org.junit.jupiter.api.Assertions.assertFalse(environmentAudit.contains("https://demo.example.com"));
        org.junit.jupiter.api.Assertions.assertTrue(environmentAudit.contains("[REDACTED]"));

        mockMvc.perform(put("/api/cicd/applications/{id}/environment", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedRevision":0,"variables":[]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(40950)));

        mockMvc.perform(put("/api/cicd/applications/{id}/environment", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedRevision":1,"variables":[
                                  {"key":"API_KEY","value":null,"secret":true,"description":"Provider token"},
                                  {"key":"PUBLIC_URL","value":"https://new.example.com","secret":false}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision", is(2)))
                .andExpect(jsonPath("$.data.variables[0].value").isEmpty())
                .andExpect(jsonPath("$.data.variables[1].value", is("https://new.example.com")));
    }

    @Test
    void releasePreflightExplainsConfigurationBlockers() throws Exception {
        Fixture fixture = createApplication();

        mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready", is(false)))
                .andExpect(jsonPath("$.data.blockerCount", is(4)))
                .andExpect(jsonPath("$.data.checks.length()", is(11)))
                .andExpect(jsonPath("$.data.checks[0].code", is("CONFIGURATION")))
                .andExpect(jsonPath("$.data.checks[0].status", is("BLOCK")))
                .andExpect(jsonPath("$.data.checks[0].action", is("CONFIGURE_CICD")))
                .andExpect(jsonPath("$.data.checks[4].code", is("AGENT")))
                .andExpect(jsonPath("$.data.checks[4].status", is("PASS")));
    }

    @Test
    void releasePreflightBecomesReadyWithFreshHealthAndCapacityEvidence() throws Exception {
        Fixture fixture = createApplication();
        mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"API",
                                 "providerBaseUrl":"https://coolify.example/api/v1","providerApiToken":"provider-token",
                                 "providerResourceId":"app_42","autoDeploy":true,"productionApproval":true,
                                 "autoRollback":true,"healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk());
        reportHealth(fixture, "HEALTHY");
        mockMvc.perform(post("/api/agent/metrics")
                        .header("X-DevPilot-Agent-Token", fixture.agentToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentVersion":"0.1.0","collectedAt":"%s","cpuUsage":12.0,
                                 "loadOne":0.2,"loadFive":0.2,"loadFifteen":0.2,
                                 "memoryTotal":16000000000,"memoryUsed":4000000000,"memoryAvailable":12000000000,
                                 "diskTotal":500000000000,"diskUsed":100000000000,"diskFree":400000000000,
                                 "networkBytesSent":1,"networkBytesReceived":1,
                                 "networkUploadRate":1.0,"networkDownloadRate":1.0}
                                """.formatted(Instant.now())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready", is(true)))
                .andExpect(jsonPath("$.data.blockerCount", is(0)))
                .andExpect(jsonPath("$.data.warningCount", is(3)))
                .andExpect(jsonPath("$.data.checks[5].status", is("PASS")))
                .andExpect(jsonPath("$.data.checks[6].status", is("PASS")))
                .andExpect(jsonPath("$.data.checks[10].code", is("ARTIFACT")))
                .andExpect(jsonPath("$.data.checks[10].status", is("WARN")));
    }

    @Test
    void dirtyEnvironmentIsSafelySyncedBeforeCoolifyDeployment() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"API",
                                 "providerBaseUrl":"https://coolify.example/api/v1","providerApiToken":"provider-token",
                                 "providerResourceId":"app_42","autoDeploy":true,"productionApproval":true,
                                 "autoRollback":true,"healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        String secret = data(configured).path("oneTimeCallbackSecret").asText();
        mockMvc.perform(put("/api/cicd/applications/{id}/environment", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedRevision":0,"variables":[
                                  {"key":"NODE_ENV","value":"production","secret":false},
                                  {"key":"API_KEY","value":"secret-value","secret":true}]}
                                """))
                .andExpect(status().isOk());

        submitSuccessfulCallback(secret, "run-env", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "ghcr.io/acme/demo:sha-eeeeeeeeeeee");

        verify(deploymentWebhookClient).syncCoolifyEnvironment("https://coolify.example/api/v1", "provider-token", "app_42",
                Map.of("API_KEY", new EnvironmentVariable("secret-value", true),
                        "NODE_ENV", new EnvironmentVariable("production", false)), Set.of());
        verify(deploymentWebhookClient).deploy("COOLIFY", "API", null,
                "https://coolify.example/api/v1", "provider-token", "app_42",
                "ghcr.io/acme/demo:sha-eeeeeeeeeeee");
        mockMvc.perform(get("/api/cicd/applications/{id}/environment", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus", is("SYNCED")))
                .andExpect(jsonPath("$.data.syncedRevision", is(1)));
    }

    @Test
    void signedSuccessfulPipelineTriggersExactlyOneDeployment() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo/",
                                 "branchName":"main","deploymentProvider":"COOLIFY",
                                 "deploymentWebhookUrl":"https://coolify.example/api/v1/deploy?uuid=demo",
                                 "autoDeploy":true,"productionApproval":true,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repositoryUrl", is("https://github.com/acme/demo")))
                .andExpect(jsonPath("$.data.callbackUrl", is("/api/cicd/webhooks/demo")))
                .andReturn();
        String secret = data(configured).path("oneTimeCallbackSecret").asText();
        String stored = jdbcTemplate.queryForObject("SELECT callback_secret_cipher FROM cicd_configuration", String.class);
        org.junit.jupiter.api.Assertions.assertNotEquals(secret, stored);
        org.junit.jupiter.api.Assertions.assertTrue(stored.startsWith("v1:"));

        String running = callback("RUNNING", "PENDING", "PENDING", null);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, running)).content(running))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployStatus", is("NOT_STARTED")));

        String invalidSuccess = callback("SUCCEEDED", "PASSED", "FAILED", "ghcr.io/acme/demo:sha-abcdef123456");
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, invalidSuccess)).content(invalidSuccess))
                .andExpect(status().isBadRequest());

        String mismatchedImage = callback("SUCCEEDED", "PASSED", "PASSED", "ghcr.io/acme/demo:sha-999999999999");
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, mismatchedImage)).content(mismatchedImage))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(40048)));

        String success = callback("SUCCEEDED", "PASSED", "PASSED", "ghcr.io/acme/demo:sha-abcdef123456");
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                            .header("X-DevPilot-Signature", sign(secret, success)).content(success))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status", is("SUCCEEDED")))
                    .andExpect(jsonPath("$.data.deployStatus", is("TRIGGERED")));
        }
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/api/v1/deploy?uuid=demo", null, null, null,
                "ghcr.io/acme/demo:sha-abcdef123456");

        mockMvc.perform(get("/api/cicd/applications/{id}/runs", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].externalRunId", is("run-42")))
                .andExpect(jsonPath("$.data[0].imageUri", is("ghcr.io/acme/demo:sha-abcdef123456")));

        jdbcTemplate.update("UPDATE cicd_deployment SET started_at = ?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(20));
        mockMvc.perform(post("/api/agent/applications/health/{id}/result", fixture.applicationId())
                        .header("X-DevPilot-Agent-Token", fixture.agentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HEALTHY\",\"latencyMillis\":18,\"httpStatus\":200,\"message\":\"ready\"}"))
                .andExpect(status().isOk());
        cicdDeploymentService.reconcileTriggered();
        mockMvc.perform(get("/api/cicd/applications/{id}/deployments", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status", is("HEALTHY")))
                .andExpect(jsonPath("$.data[0].providerDeploymentId").isEmpty());

        mockMvc.perform(get("/api/cicd/activity").param("limit", "5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applicationName", is("Demo")))
                .andExpect(jsonPath("$.data[0].environment", is("PRODUCTION")))
                .andExpect(jsonPath("$.data[0].serverName", is("production")))
                .andExpect(jsonPath("$.data[0].imageUri", is("ghcr.io/acme/demo:sha-abcdef123456")))
                .andExpect(jsonPath("$.data[0].status", is("HEALTHY")));

        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", "sha256=00").content(success))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void successfulReleasesAreSerializedPerApplicationAndQueuedDurably() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"WEBHOOK",
                                 "deploymentWebhookUrl":"https://coolify.example/deploy/demo",
                                 "autoDeploy":true,"productionApproval":true,"autoRollback":true,
                                 "healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        String secret = data(configured).path("oneTimeCallbackSecret").asText();
        String imageA = "ghcr.io/acme/demo:sha-aaaaaaaaaaaa";
        String imageB = "ghcr.io/acme/demo:sha-bbbbbbbbbbbb";

        submitSuccessfulCallback(secret, "run-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", imageA);
        String queued = callback("run-b", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "SUCCEEDED", "PASSED", "PASSED", imageB);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, queued)).content(queued))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployStatus", is("QUEUED")))
                .andExpect(jsonPath("$.data.deployError").value(org.hamcrest.Matchers.containsString("自动继续")));

        org.junit.jupiter.api.Assertions.assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cicd_deployment", Long.class));
        org.junit.jupiter.api.Assertions.assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cicd_pipeline_run WHERE deploy_status = 'QUEUED'", Long.class));
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, imageA);

        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        cicdDeploymentService.reconcileQueued();

        org.junit.jupiter.api.Assertions.assertEquals("TRIGGERED", jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM cicd_pipeline_run WHERE external_run_id = 'run-b'", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cicd_deployment", Long.class));
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, imageB);
    }

    @Test
    void criticalDiskWatermarkPausesAndAutomaticallyResumesRelease() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"WEBHOOK",
                                 "deploymentWebhookUrl":"https://coolify.example/deploy/demo",
                                 "autoDeploy":true,"productionApproval":true,"autoRollback":true,
                                 "healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        String secret = data(configured).path("oneTimeCallbackSecret").asText();
        Long serverId = jdbcTemplate.queryForObject("SELECT server_id FROM application WHERE id = ?", Long.class,
                Long.parseLong(fixture.applicationId()));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long gib = 1024L * 1024L * 1024L;
        jdbcTemplate.update("""
                INSERT INTO server_metric
                (id, server_id, collected_at, sample_count, cpu_usage, load_one, load_five, load_fifteen,
                 memory_total, memory_used, memory_available, disk_total, disk_used, disk_free,
                 network_bytes_sent, network_bytes_received, network_upload_rate, network_download_rate,
                 created_at, updated_at)
                VALUES (?, ?, ?, 1, 5, 0.1, 0.1, 0.1, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?)
                """, 991L, serverId, now, 8 * gib, 2 * gib, 6 * gib,
                100 * gib, 96 * gib, 4 * gib, now, now);

        String image = "ghcr.io/acme/demo:sha-cccccccccccc";
        String body = callback("run-disk", "cccccccccccccccccccccccccccccccccccccccc",
                "SUCCEEDED", "PASSED", "PASSED", image);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, body)).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployStatus", is("QUEUED")))
                .andExpect(jsonPath("$.data.deployError").value(org.hamcrest.Matchers.containsString("磁盘保护")));
        org.junit.jupiter.api.Assertions.assertEquals(0L,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Long.class));

        jdbcTemplate.update("UPDATE server_metric SET disk_used = ?, disk_free = ? WHERE id = ?",
                50 * gib, 50 * gib, 991L);
        cicdDeploymentService.reconcileQueued();

        org.junit.jupiter.api.Assertions.assertEquals("TRIGGERED", jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM cicd_pipeline_run WHERE external_run_id = 'run-disk'", String.class));
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, image);
    }

    @Test
    void releasePreflightQueuesWhileAgentIsOfflineAndResumesWhenOnline() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"WEBHOOK",
                                 "deploymentWebhookUrl":"https://coolify.example/deploy/demo",
                                 "autoDeploy":true,"productionApproval":true,"autoRollback":true,
                                 "healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        String secret = data(configured).path("oneTimeCallbackSecret").asText();
        jdbcTemplate.update("UPDATE server_node SET agent_status = 'OFFLINE'");
        String image = "ghcr.io/acme/demo:sha-dddddddddddd";
        String body = callback("run-offline", "dddddddddddddddddddddddddddddddddddddddd",
                "SUCCEEDED", "PASSED", "PASSED", image);

        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, body)).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployStatus", is("QUEUED")))
                .andExpect(jsonPath("$.data.deployError")
                        .value(org.hamcrest.Matchers.containsString("Agent 连接")));
        org.junit.jupiter.api.Assertions.assertEquals(0L,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Long.class));

        jdbcTemplate.update("UPDATE server_node SET agent_status = 'ONLINE', last_heartbeat = ?",
                LocalDateTime.now(ZoneOffset.UTC));
        cicdDeploymentService.reconcileQueued();

        org.junit.jupiter.api.Assertions.assertEquals("TRIGGERED", jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM cicd_pipeline_run WHERE external_run_id = 'run-offline'", String.class));
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, image);
    }

    @Test
    void healthyStagingArtifactPromotesToProductionWithoutRebuild() throws Exception {
        Fixture fixture = createApplication("STAGING");
        Long serverId = jdbcTemplate.queryForObject("SELECT server_id FROM application WHERE id = ?", Long.class,
                Long.parseLong(fixture.applicationId()));
        Long containerId = jdbcTemplate.queryForObject("SELECT container_snapshot_id FROM application WHERE id = ?",
                Long.class, Long.parseLong(fixture.applicationId()));
        MvcResult production = mockMvc.perform(post("/api/applications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Demo Production","code":"demo-prod","environment":"PRODUCTION",
                                 "serverId":"%s","containerSnapshotId":"%s",
                                 "healthCheckUrl":"http://127.0.0.1:8080/actuator/health",
                                 "accessUrl":"https://demo.example.com"}
                                """.formatted(serverId, containerId)))
                .andExpect(status().isOk()).andReturn();
        String productionId = data(production).path("id").asText();

        MvcResult stagingConfiguration = configureWebhook(
                fixture, fixture.applicationId(), "https://coolify.example/deploy/staging", true);
        configureWebhook(fixture, productionId, "https://coolify.example/deploy/production", false);
        String secret = data(stagingConfiguration).path("oneTimeCallbackSecret").asText();
        String image = "ghcr.io/acme/demo:sha-eeeeeeeeeeee";
        submitSuccessfulCallback(secret, "run-staging", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", image);
        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        Long sourceDeploymentId = jdbcTemplate.queryForObject(
                "SELECT id FROM cicd_deployment WHERE application_id = ? AND status = 'HEALTHY'", Long.class,
                Long.parseLong(fixture.applicationId()));

        mockMvc.perform(get("/api/cicd/applications/{id}/promotion-targets", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].applicationId", is(productionId)))
                .andExpect(jsonPath("$.data[0].environment", is("PRODUCTION")))
                .andExpect(jsonPath("$.data[0].ready", is(true)));

        mockMvc.perform(post("/api/cicd/applications/{applicationId}/deployments/{deploymentId}/promote",
                        fixture.applicationId(), sourceDeploymentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetApplicationId\":\"%s\"}".formatted(productionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationId", is(productionId)))
                .andExpect(jsonPath("$.data.deploymentKind", is("PROMOTION")))
                .andExpect(jsonPath("$.data.promotedFromApplicationId", is(fixture.applicationId())))
                .andExpect(jsonPath("$.data.promotedFromDeploymentId", is(sourceDeploymentId.toString())))
                .andExpect(jsonPath("$.data.imageUri", is(image)))
                .andExpect(jsonPath("$.data.status", is("TRIGGERED")));
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/staging", null, null, null, image);
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/production", null, null, null, image);

        jdbcTemplate.update("UPDATE cicd_deployment SET started_at = ? WHERE application_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(20), Long.parseLong(productionId));
        mockMvc.perform(post("/api/agent/applications/health/{id}/result", productionId)
                        .header("X-DevPilot-Agent-Token", fixture.agentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HEALTHY\",\"latencyMillis\":20,\"httpStatus\":200,\"message\":\"production ready\"}"))
                .andExpect(status().isOk());
        cicdDeploymentService.reconcileTriggered();

        mockMvc.perform(get("/api/cicd/applications/{id}/deployments", productionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].deploymentKind", is("PROMOTION")))
                .andExpect(jsonPath("$.data[0].status", is("HEALTHY")))
                .andExpect(jsonPath("$.data[0].imageUri", is(image)));

        mockMvc.perform(post("/api/cicd/applications/{applicationId}/deployments/{deploymentId}/promote",
                        fixture.applicationId(), sourceDeploymentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetApplicationId\":\"%s\"}".formatted(productionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(40954)));
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/production", null, null, null, image);
    }

    @Test
    void trustedPullRequestCreatesIsolatedPreviewAndCloseRemovesIt() throws Exception {
        Fixture fixture = createApplication();
        when(deploymentWebhookClient.deployPreview("COOLIFY", "API", "https://coolify.example/api/v1",
                "provider-token", "app_42", 27, "ghcr.io/acme/demo:sha-abcdef123456"))
                .thenReturn("preview-deployment-27");

        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"API",
                                 "providerBaseUrl":"https://coolify.example/api/v1","providerApiToken":"provider-token",
                                 "providerResourceId":"app_42","autoDeploy":true,"productionApproval":true,
                                 "autoRollback":true,"healthTimeoutSeconds":60,"previewEnabled":true,
                                 "previewUrlTemplate":"https://pr-{{pr_id}}.preview.example.com",
                                 "previewTtlHours":24,"rotatePreviewCallbackSecret":false,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewEnabled", is(true)))
                .andExpect(jsonPath("$.data.previewCallbackSecretConfigured", is(true)))
                .andExpect(jsonPath("$.data.previewCallbackUrl", is("/api/cicd/webhooks/demo/previews")))
                .andReturn();
        JsonNode configuration = data(configured);
        String previewSecret = configuration.path("oneTimePreviewCallbackSecret").asText();
        String productionSecret = configuration.path("oneTimeCallbackSecret").asText();
        org.junit.jupiter.api.Assertions.assertFalse(previewSecret.isBlank());
        org.junit.jupiter.api.Assertions.assertNotEquals(productionSecret, previewSecret);

        String deploy = """
                {"action":"DEPLOY","pullRequestId":27,"baseBranch":"main",
                 "externalRunId":"github-2701","title":"Improve dashboard","branchName":"feature/dashboard",
                 "commitSha":"abcdef1234567890abcdef1234567890abcdef12","status":"SUCCEEDED",
                 "testStatus":"PASSED","securityStatus":"PASSED",
                 "imageUri":"ghcr.io/acme/demo:sha-abcdef123456",
                 "runUrl":"https://github.com/acme/demo/actions/runs/2701"}
                """;
        mockMvc.perform(post("/api/cicd/webhooks/demo/previews").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(productionSecret, deploy)).content(deploy))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/cicd/webhooks/demo/previews").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(previewSecret, deploy)).content(deploy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pullRequestId", is(27)))
                .andExpect(jsonPath("$.data.status", is("DEPLOYING")))
                .andExpect(jsonPath("$.data.previewUrl", is("https://pr-27.preview.example.com")))
                .andExpect(jsonPath("$.data.providerDeploymentId", is("preview-deployment-27")));
        mockMvc.perform(post("/api/cicd/webhooks/demo/previews").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(previewSecret, deploy)).content(deploy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerDeploymentId", is("preview-deployment-27")));
        verify(deploymentWebhookClient).deployPreview("COOLIFY", "API", "https://coolify.example/api/v1",
                "provider-token", "app_42", 27, "ghcr.io/acme/demo:sha-abcdef123456");

        when(deploymentWebhookClient.fetchDeploymentState("COOLIFY", "https://coolify.example/api/v1",
                "provider-token", "app_42", "preview-deployment-27")).thenReturn(DeploymentState.SUCCEEDED);
        cicdPreviewService.reconcileDeployments();
        mockMvc.perform(get("/api/cicd/applications/{id}/previews", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].status", is("READY")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/applications/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(40956)));
        mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"release","deploymentProvider":"COOLIFY","deploymentMode":"API",
                                 "providerResourceId":"app_42","autoDeploy":true,"productionApproval":true,
                                 "autoRollback":true,"healthTimeoutSeconds":60,"previewEnabled":true,
                                 "previewUrlTemplate":"https://pr-{{pr_id}}.preview.example.com",
                                 "previewTtlHours":24,"rotatePreviewCallbackSecret":false,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(40955)));

        String close = "{\"action\":\"CLOSE\",\"pullRequestId\":27,\"baseBranch\":\"main\"}";
        mockMvc.perform(post("/api/cicd/webhooks/demo/previews").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(previewSecret, close)).content(close))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DELETED")));
        mockMvc.perform(post("/api/cicd/webhooks/demo/previews").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(previewSecret, close)).content(close))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DELETED")));
        verify(deploymentWebhookClient).deletePreview("COOLIFY", "API", "https://coolify.example/api/v1",
                "provider-token", "app_42", 27);

        jdbcTemplate.query("SELECT preview_callback_secret_cipher FROM cicd_configuration", result -> {
            String encrypted = result.getString(1);
            org.junit.jupiter.api.Assertions.assertTrue(encrypted.startsWith("v1:"));
            org.junit.jupiter.api.Assertions.assertFalse(encrypted.contains(previewSecret));
        });
    }

    @Test
    void expiredPreviewCleanupRetriesAfterProviderFailure() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"API",
                                 "providerBaseUrl":"https://coolify.example/api/v1","providerApiToken":"provider-token",
                                 "providerResourceId":"app_42","autoDeploy":true,"productionApproval":true,
                                 "autoRollback":true,"healthTimeoutSeconds":60,"previewEnabled":true,
                                 "previewUrlTemplate":"https://pr-{{pr_id}}.preview.example.com",
                                 "previewTtlHours":1,"rotatePreviewCallbackSecret":false,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        String previewSecret = data(configured).path("oneTimePreviewCallbackSecret").asText();
        when(deploymentWebhookClient.deployPreview("COOLIFY", "API", "https://coolify.example/api/v1",
                "provider-token", "app_42", 31, "ghcr.io/acme/demo:sha-123456789abc"))
                .thenReturn("preview-deployment-31");
        String deploy = """
                {"action":"DEPLOY","pullRequestId":31,"baseBranch":"main",
                 "externalRunId":"github-3101","title":"Retry cleanup","branchName":"feature/retry",
                 "commitSha":"123456789abcdef0123456789abcdef012345678","status":"SUCCEEDED",
                 "testStatus":"PASSED","securityStatus":"PASSED",
                 "imageUri":"ghcr.io/acme/demo:sha-123456789abc"}
                """;
        mockMvc.perform(post("/api/cicd/webhooks/demo/previews").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(previewSecret, deploy)).content(deploy))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE cicd_preview SET expires_at = ? WHERE pull_request_id = 31",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        doThrow(new IllegalStateException("provider temporarily unavailable")).doNothing()
                .when(deploymentWebhookClient).deletePreview("COOLIFY", "API",
                        "https://coolify.example/api/v1", "provider-token", "app_42", 31);

        cicdPreviewService.cleanupExpired();
        org.junit.jupiter.api.Assertions.assertEquals("CLEANUP_FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_preview WHERE pull_request_id = 31", String.class));
        org.junit.jupiter.api.Assertions.assertTrue(jdbcTemplate.queryForObject(
                "SELECT expires_at FROM cicd_preview WHERE pull_request_id = 31", LocalDateTime.class)
                .isAfter(LocalDateTime.now(ZoneOffset.UTC)));

        jdbcTemplate.update("UPDATE cicd_preview SET expires_at = ? WHERE pull_request_id = 31",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        cicdPreviewService.cleanupExpired();
        org.junit.jupiter.api.Assertions.assertEquals("DELETED", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_preview WHERE pull_request_id = 31", String.class));
        verify(deploymentWebhookClient, times(2)).deletePreview("COOLIFY", "API",
                "https://coolify.example/api/v1", "provider-token", "app_42", 31);
    }

    @Test
    void unhealthyReleaseAutomaticallyRollsBackAndHealthyTargetSupportsManualRollback() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"WEBHOOK",
                                 "deploymentWebhookUrl":"https://coolify.example/deploy/demo",
                                 "autoDeploy":true,"productionApproval":true,"autoRollback":true,
                                 "healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        String secret = data(configured).path("oneTimeCallbackSecret").asText();
        String imageA = "ghcr.io/acme/demo:sha-111111111111";
        String imageB = "ghcr.io/acme/demo:sha-222222222222";
        String imageC = "ghcr.io/acme/demo:sha-333333333333";

        submitSuccessfulCallback(secret, "run-a", "1111111111111111111111111111111111111111", imageA);
        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();

        submitSuccessfulCallback(secret, "run-b", "2222222222222222222222222222222222222222", imageB);
        reportHealth(fixture, "UNHEALTHY");
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("ROLLBACK_TRIGGERED", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_deployment WHERE image_uri = ? AND deployment_kind = 'RELEASE'", String.class, imageB));
        org.junit.jupiter.api.Assertions.assertEquals(imageA, jdbcTemplate.queryForObject(
                "SELECT image_uri FROM cicd_deployment WHERE deployment_kind = 'ROLLBACK' AND status = 'TRIGGERED'", String.class));

        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("sha-111111111111", jdbcTemplate.queryForObject(
                "SELECT current_version FROM application WHERE id = ?", String.class, fixture.applicationId()));
        org.junit.jupiter.api.Assertions.assertEquals("ROLLED_BACK", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_deployment WHERE image_uri = ? AND deployment_kind = 'RELEASE'", String.class, imageB));

        submitSuccessfulCallback(secret, "run-c", "3333333333333333333333333333333333333333", imageC);
        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        Long healthyA = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM cicd_deployment WHERE image_uri = ? AND status = 'HEALTHY'", Long.class, imageA);
        mockMvc.perform(post("/api/cicd/applications/{applicationId}/deployments/{deploymentId}/rollback",
                        fixture.applicationId(), healthyA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deploymentKind", is("ROLLBACK")))
                .andExpect(jsonPath("$.data.imageUri", is(imageA)))
                .andExpect(jsonPath("$.data.status", is("TRIGGERED")));

        verify(deploymentWebhookClient, times(3)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, imageA);
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, imageB);
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, imageC);

        // Even with auto rollback enabled, a failed rollback must not recurse.
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class);
        reportHealth(fixture, "UNHEALTHY");
        cicdDeploymentService.reconcileTriggered();
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals(before,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cicd_deployment WHERE deployment_kind = 'ROLLBACK' AND status = 'UNHEALTHY'", Integer.class));
    }

    @Test
    void rejectedAutomaticRollbackFinishesFailedReleaseWithoutRewritingHealthyHistory() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"WEBHOOK",
                                 "deploymentWebhookUrl":"https://coolify.example/deploy/demo",
                                 "autoDeploy":true,"productionApproval":true,"autoRollback":true,
                                 "healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """))
                .andExpect(status().isOk()).andReturn();
        String secret = data(configured).path("oneTimeCallbackSecret").asText();
        String healthyImage = "ghcr.io/acme/demo:sha-111111111111";
        submitSuccessfulCallback(secret, "healthy-run", "1111111111111111111111111111111111111111", healthyImage);
        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        when(deploymentWebhookClient.deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, healthyImage))
                .thenThrow(new IllegalStateException("provider rejected rollback"));
        submitSuccessfulCallback(secret, "failed-run", "2222222222222222222222222222222222222222",
                "ghcr.io/acme/demo:sha-222222222222");
        reportHealth(fixture, "UNHEALTHY");
        cicdDeploymentService.reconcileTriggered();
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("ROLLBACK_FAILED", jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM cicd_pipeline_run WHERE external_run_id = 'failed-run'", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("HEALTHY", jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM cicd_pipeline_run WHERE external_run_id = 'healthy-run'", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cicd_deployment WHERE status = 'ROLLBACK_FAILED'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cicd_deployment WHERE deployment_kind = 'ROLLBACK' AND status = 'FAILED'", Integer.class));
    }

    @Test
    void rollbackRequiresOptInAndOmittedUpdatePreservesChoice() throws Exception {
        Fixture fixture = createApplication();
        String body = """
                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"WEBHOOK",
                 "deploymentWebhookUrl":"https://coolify.example/deploy/demo",
                 "autoDeploy":true,"productionApproval":true,"rotateCallbackSecret":false}
                """;
        mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.autoRollback", is(false)));
        mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"autoDeploy\"", "\"autoRollback\":true,\"autoDeploy\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.autoRollback", is(true)));
        mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.autoRollback", is(true)));
    }

    @Test
    void coolifyOldHealthyEndpointCannotCompleteAnUnfinishedDeployment() throws Exception {
        Fixture fixture = createApplication();
        MvcResult result = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"API",
                 "providerBaseUrl":"https://coolify.example","providerApiToken":"token",
                 "providerResourceId":"app_42","autoDeploy":true,"productionApproval":true,
                 "autoRollback":false,"rotateCallbackSecret":false}
                """)).andExpect(status().isOk()).andReturn();
        when(deploymentWebhookClient.deploy("COOLIFY", "API", null, "https://coolify.example", "token",
                "app_42", "ghcr.io/acme/demo:sha-abcdef123456")).thenReturn("deploy-1");
        submitSuccessfulCallback(data(result).path("oneTimeCallbackSecret").asText(), "new-run",
                "abcdef123456abcdef123456abcdef123456abcdef", "ghcr.io/acme/demo:sha-abcdef123456");
        when(deploymentWebhookClient.fetchDeploymentState("COOLIFY", "https://coolify.example", "token",
                "app_42", "deploy-1")).thenReturn(DeploymentState.UNKNOWN);
        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("TRIGGERED", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_deployment", String.class));
        when(deploymentWebhookClient.fetchDeploymentState("COOLIFY", "https://coolify.example", "token",
                "app_42", "deploy-1")).thenReturn(DeploymentState.SUCCEEDED);
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("VERIFYING", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_deployment", String.class));
        // The pre-completion probe remains insufficient.
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("VERIFYING", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_deployment", String.class));
        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("VERIFYING", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_deployment", String.class));
        jdbcTemplate.update("UPDATE docker_container_snapshot SET image = ?, last_seen_at = ?",
                "ghcr.io/acme/demo:sha-abcdef123456", LocalDateTime.now(ZoneOffset.UTC));
        cicdDeploymentService.reconcileTriggered();
        org.junit.jupiter.api.Assertions.assertEquals("HEALTHY", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_deployment", String.class));
    }

    @Test
    void applicationFollowsReplacementContainerInsteadOfOldSnapshot() throws Exception {
        Fixture fixture = createApplication();
        var payload = objectMapper.readTree(snapshotPayload());
        var container = (com.fasterxml.jackson.databind.node.ObjectNode) payload.path("containers").get(0);
        container.put("containerId", "replacement-container-id");
        container.put("image", "ghcr.io/acme/demo:sha-222222222222");
        mockMvc.perform(post("/api/agent/docker/snapshot")
                .header("X-DevPilot-Agent-Token", fixture.agentToken())
                .contentType(MediaType.APPLICATION_JSON).content(payload.toString())).andExpect(status().isOk());
        mockMvc.perform(get("/api/applications/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.containerId", is("replacement-container-id")))
                .andExpect(jsonPath("$.data.dockerImage", is("ghcr.io/acme/demo:sha-222222222222")))
                .andExpect(jsonPath("$.data.status", is("RUNNING")));
    }

    @Test
    void newApplicationDoesNotNeedAnExistingContainer() throws Exception {
        Fixture fixture = createApplication();
        String serverId = jdbcTemplate.queryForObject("SELECT server_id FROM application WHERE id = ?", String.class, fixture.applicationId());
        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"New project","code":"first-deploy","environment":"PRODUCTION","serverId":"%s"}
                """.formatted(serverId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.containerSnapshotId").doesNotExist());
    }

    @Test
    void onboardingResumesFailedStepDoesNotRecreateApplicationAndClearsSecrets() throws Exception {
        Fixture fixture = createApplication();
        var repo = new com.devpilot.server.cicd.onboarding.RepositoryOnboardingClient.Repository(
                "https://api.github.com/repos/acme/demo", "acme/demo", "main", "ghcr.io/acme/demo", "FROM node:22", "NODE", "https://github.com/acme/demo");
        when(onboardingRepositories.inspect("GITHUB", "https://github.com/acme/demo", "repository-secret")).thenReturn(repo);
        when(onboardingProviders.discover("DOKPLOY", "https://deploy.example", "provider-secret"))
                .thenReturn(new com.devpilot.server.cicd.onboarding.ProviderOnboardingClient.Discovery(
                        java.util.List.of(new com.devpilot.server.cicd.onboarding.ProviderOnboardingClient.Target("p", "e", "Personal")),
                        java.util.List.of(new com.devpilot.server.cicd.onboarding.ProviderOnboardingClient.Server("", "Local", ""))));
        when(onboardingProviders.ensureApplication(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("demo"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.devpilot.server.cicd.onboarding.ProviderOnboardingClient.Application("app1", "swarm:app1"));
        org.mockito.Mockito.doThrow(new com.devpilot.server.cicd.onboarding.OnboardingHttpClient.RemoteFailure("DOKPLOY POST HTTP 503", 503))
                .doNothing().when(onboardingProviders).configure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("app1"));
        when(onboardingRepositories.proposeWorkflow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("demo"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("https://github.com/acme/demo/pull/1");
        String request = """
                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo","repositoryToken":"repository-secret",
                 "deploymentProvider":"DOKPLOY","providerBaseUrl":"https://deploy.example","providerApiToken":"provider-secret",
                 "projectId":"p","environmentId":"e","providerServerId":"","publicBaseUrl":"https://ops.example",
                 "containerPort":8080,"hostPort":18081,"healthPath":"/health","imageRepository":"ghcr.io/acme/demo",
                 "branch":"main","workflowContent":"workflow_dispatch","environmentValues":{"DATABASE_PASSWORD":"runtime-secret"}}
                """;
        for (int repeat = 0; repeat < 2; repeat++) {
            mockMvc.perform(post("/api/cicd/onboarding/{id}", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stage", is(0)))
                    .andExpect(jsonPath("$.data.requestCipher").doesNotExist());
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_onboarding", Integer.class));
        String stored = jdbcTemplate.queryForObject("SELECT request_cipher FROM cicd_onboarding", String.class);
        org.junit.jupiter.api.Assertions.assertFalse(stored.contains("repository-secret"));
        jdbcTemplate.update("UPDATE server_node SET listening_tcp_ports = '', ports_collected_at = ?, agent_status = 'ONLINE'",
                LocalDateTime.now(ZoneOffset.UTC));
        for (int stage = 0; stage < 3; stage++) {
            mockMvc.perform(post("/api/cicd/onboarding/{id}/advance", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk());
        }
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT status FROM cicd_onboarding", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject("SELECT stage FROM cicd_onboarding", Integer.class));
        for (int stage = 2; stage < 5; stage++) {
            mockMvc.perform(post("/api/cicd/onboarding/{id}/advance", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk());
            if (stage == 2) {
                mockMvc.perform(put("/api/cicd/onboarding/{id}/credentials", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"registryPassword\":\"replacement-password\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.stage", is(3)));
                verify(onboardingProviders).refreshRegistryCredentials(org.mockito.ArgumentMatchers.argThat(
                        changed -> "replacement-password".equals(changed.registryPassword())), org.mockito.ArgumentMatchers.eq("app1"));
            }
        }
        mockMvc.perform(get("/api/cicd/onboarding/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("AWAITING_MERGE")))
                .andExpect(jsonPath("$.data.changeUrl", is("https://github.com/acme/demo/pull/1")));
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject("SELECT request_cipher FROM cicd_onboarding", String.class));
        verify(onboardingProviders, times(1)).ensureApplication(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("demo"), org.mockito.ArgumentMatchers.anyString());
        verify(deploymentWebhookClient, times(0)).deploy(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void submitSuccessfulCallback(String secret, String runId, String commitSha, String image) throws Exception {
        String body = callback(runId, commitSha, "SUCCEEDED", "PASSED", "PASSED", image);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, body)).content(body))
                .andExpect(status().isOk());
    }

    private void reportHealth(Fixture fixture, String health) throws Exception {
        jdbcTemplate.update("UPDATE cicd_deployment SET started_at = ? WHERE status = 'TRIGGERED'",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(20));
        mockMvc.perform(post("/api/agent/applications/health/{id}/result", fixture.applicationId())
                        .header("X-DevPilot-Agent-Token", fixture.agentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\",\"latencyMillis\":18,\"httpStatus\":200,\"message\":\"probe\"}"
                                .formatted(health)))
                .andExpect(status().isOk());
    }

    private Fixture createApplication() throws Exception {
        return createApplication("PRODUCTION");
    }

    private Fixture createApplication(String environment) throws Exception {
        String accessToken = setupAdministrator();
        MvcResult createdServer = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"production\"}"))
                .andExpect(status().isOk()).andReturn();
        String agentToken = data(createdServer).path("agentToken").asText();
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"%s","hostname":"prod-host","ip":"10.0.0.30","os":"Linux",
                         "kernel":"6.8","arch":"amd64","agentVersion":"0.1.0","cpuModel":"CPU",
                         "cpuCores":8,"memoryTotal":16000000000,"diskTotal":500000000000}
                        """.formatted(agentToken))).andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/docker/snapshot")
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON).content(snapshotPayload()))
                .andExpect(status().isOk());
        MvcResult containers = mockMvc.perform(get("/api/docker/containers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        String containerId = data(containers).get(0).path("id").asText();
        String serverId = data(containers).get(0).path("serverId").asText();
        MvcResult app = mockMvc.perform(post("/api/applications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Demo","code":"demo","environment":"%s",
                                 "serverId":"%s","containerSnapshotId":"%s",
                                 "healthCheckUrl":"http://127.0.0.1:8080/actuator/health"}
                                """.formatted(environment, serverId, containerId)))
                .andExpect(status().isOk()).andReturn();
        return new Fixture(accessToken, data(app).path("id").asText(), agentToken);
    }

    private MvcResult configureWebhook(Fixture fixture, String applicationId, String webhook,
                                       boolean autoDeploy) throws Exception {
        return mockMvc.perform(put("/api/cicd/configurations/{id}", applicationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo",
                                 "branchName":"main","deploymentProvider":"COOLIFY","deploymentMode":"WEBHOOK",
                                 "deploymentWebhookUrl":"%s","autoDeploy":%s,"productionApproval":true,
                                 "autoRollback":true,"healthTimeoutSeconds":60,"rotateCallbackSecret":false}
                                """.formatted(webhook, autoDeploy)))
                .andExpect(status().isOk()).andReturn();
    }

    private String callback(String status, String test, String security, String image) throws Exception {
        return callback("run-42", "abcdef1234567890abcdef1234567890abcdef12", status, test, security, image);
    }

    private String callback(String runId, String commitSha, String status, String test, String security, String image) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("externalRunId", runId);
        node.put("status", status);
        node.put("testStatus", test);
        node.put("securityStatus", security);
        node.put("commitSha", commitSha);
        node.put("branchName", "main");
        if (image != null) node.put("imageUri", image);
        node.put("runUrl", "https://github.com/acme/demo/actions/runs/42");
        node.put("summary", "tests and scan completed");
        return objectMapper.writeValueAsString(node);
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String snapshotPayload() {
        return """
                {"agentVersion":"0.1.0","available":true,"engineVersion":"28.3.3","images":1,
                 "volumes":0,"networks":1,"collectedAt":"%s","containers":[
                  {"containerId":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                   "name":"demo","image":"ghcr.io/acme/demo:sha-old1234","state":"running","status":"Up",
                   "health":"healthy","cpuUsage":1,"memoryUsage":1,"memoryLimit":2,
                   "networkRx":1,"networkTx":1,"ports":[],"createdAt":"2026-08-30T00:00:00Z",
                   "startedAt":"2026-08-31T00:00:00Z","restartCount":0,"volumes":[],"environment":[]}]}
                """.formatted(Instant.now());
    }

    private String setupAdministrator() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"DevPilot-Admin-2026",
                                 "confirmPassword":"DevPilot-Admin-2026","displayName":"Administrator"}
                                """))
                .andExpect(status().isOk()).andReturn();
        return data(result).path("accessToken").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private record Fixture(String accessToken, String applicationId, String agentToken) {}
}

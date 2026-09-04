package com.devpilot.server;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devpilot.server.cicd.service.DeploymentWebhookClient;
import com.devpilot.server.cicd.service.CicdDeploymentService;
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
    @MockitoBean private DeploymentWebhookClient deploymentWebhookClient;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM application_environment_variable");
        jdbcTemplate.update("DELETE FROM application_environment_state");
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
                                {"name":"Demo","code":"demo","environment":"PRODUCTION",
                                 "serverId":"%s","containerSnapshotId":"%s",
                                 "healthCheckUrl":"http://127.0.0.1:8080/actuator/health"}
                                """.formatted(serverId, containerId)))
                .andExpect(status().isOk()).andReturn();
        return new Fixture(accessToken, data(app).path("id").asText(), agentToken);
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

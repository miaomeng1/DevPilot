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
    private String fixtureAdministratorToken;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CicdDeploymentService cicdDeploymentService;
    @Autowired private CicdPreviewService cicdPreviewService;
    @Autowired private com.devpilot.server.cicd.onboarding.OnboardingService onboardingService;
    @Autowired private com.devpilot.server.node.service.ServerNodeService serverNodes;
    @Autowired private com.devpilot.server.cicd.service.ManualReleaseApprovalService manualApprovals;
    @Autowired private com.devpilot.server.cicd.service.GithubRunReconciler githubReconciler;
    @Autowired private com.devpilot.server.cicd.service.GithubObserverConfigurationService githubObserverConfiguration;
    @Autowired private com.devpilot.server.security.SensitiveSettingCipher settingCipher;
    @Autowired private com.devpilot.server.application.service.ApplicationService applications;
    @Autowired private com.devpilot.server.observability.DevPilotMetrics devPilotMetrics;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @MockitoBean private DeploymentWebhookClient deploymentWebhookClient;
    @MockitoBean private com.devpilot.server.cicd.service.GithubRunClient githubRunClient;
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
    void platformSetupSeparatesSavedAndVerifiedAndDoesNotExposeCredentials() throws Exception {
        Fixture fixture = createApplication();
        mockMvc.perform(get("/api/setup")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"revision":"initial","publicUrl":"https://ops.example","providerUrl":"https://deploy.example","providerApiToken":"setup-secret"}
                  """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerStatus", is("SAVED_UNVERIFIED")))
                .andExpect(jsonPath("$.data.publicUrlStatus", is("MANUAL_REQUIRED")))
                .andExpect(jsonPath("$.data.providerApiToken").doesNotExist());
        org.junit.jupiter.api.Assertions.assertFalse(jdbcTemplate.queryForObject("SELECT provider_token_cipher FROM platform_setup", String.class).contains("setup-secret"));
        String audited = jdbcTemplate.queryForObject("SELECT request_params FROM audit_log WHERE action='UPDATE_PLATFORM_SETUP'", String.class);
        org.junit.jupiter.api.Assertions.assertFalse(audited.contains("setup-secret"));
        org.junit.jupiter.api.Assertions.assertTrue(audited.contains("[REDACTED]"));
        verify(onboardingProviders, times(0)).discover(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        mockMvc.perform(post("/api/setup/verify-provider").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerStatus", is("VERIFIED")));
        verify(onboardingProviders).discover("DOKPLOY", "https://deploy.example", "setup-secret");
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='VERIFY_PLATFORM_PROVIDER'", Integer.class));
        String revision = jdbcTemplate.queryForObject("SELECT revision FROM platform_setup", String.class);
        String inspection = """
          {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo","repositoryToken":"repo-key",
           "deploymentProvider":"DOKPLOY","providerBaseUrl":"https://untrusted.example","providerApiToken":"ignored-key",
           "usePlatformConnection":true,"platformRevision":"%s"}
          """.formatted(revision);
        mockMvc.perform(post("/api/cicd/onboarding/inspect").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(inspection)).andExpect(status().isOk());
        verify(onboardingProviders, times(2)).discover("DOKPLOY", "https://deploy.example", "setup-secret");
        verify(onboardingProviders, times(0)).discover("DOKPLOY", "https://untrusted.example", "ignored-key");
        mockMvc.perform(post("/api/cicd/onboarding/inspect").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(inspection.replace(revision, "initial"))).andExpect(status().isConflict());
        String startPlan = """
          {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo","repositoryToken":"repo-key",
           "deploymentProvider":"DOKPLOY","usePlatformConnection":true,"platformRevision":"%s",
           "publicBaseUrl":"https://ops.example","containerPort":8080,"hostPort":18081,"healthPath":"/health",
           "imageRepository":"ghcr.io/acme/demo","branch":"main","workflowContent":"workflow_dispatch","providerQuotaConfirmed":true}
          """.formatted(revision);
        mockMvc.perform(post("/api/cicd/onboarding/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(startPlan)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage", is(0))).andExpect(jsonPath("$.data.providerApiToken").doesNotExist());
        JsonNode savedPlan = objectMapper.readTree(settingCipher.decrypt(jdbcTemplate.queryForObject("SELECT request_cipher FROM cicd_onboarding", String.class)));
        org.junit.jupiter.api.Assertions.assertEquals("setup-secret", savedPlan.path("providerApiToken").asText());
        org.junit.jupiter.api.Assertions.assertEquals("https://deploy.example", savedPlan.path("providerBaseUrl").asText());
        mockMvc.perform(put("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":\"" + revision + "\",\"publicUrl\":\"https://ops.example\",\"providerUrl\":\"https://other.example\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":\"initial\",\"publicUrl\":\"https://ops.example\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void setupDoesNotVerifyOfflineStaleOrFutureAgentEvidence() throws Exception {
        Fixture fixture = createApplication();
        Long serverId = jdbcTemplate.queryForObject("SELECT server_id FROM application WHERE id=?", Long.class, fixture.applicationId());
        jdbcTemplate.update("UPDATE platform_setup SET server_id=? WHERE id=1", serverId);
        String[][] cases = {{"ONLINE", "0", "VERIFIED"}, {"OFFLINE", "0", "FAILED"},
                {"ONLINE", "-600", "FAILED"}, {"ONLINE", "600", "MANUAL_REQUIRED"},
                {"UNKNOWN", "0", "SAVED_UNVERIFIED"}, {"ONLINE", "10", "VERIFIED"}};
        for (String[] scenario : cases) {
            jdbcTemplate.update("UPDATE server_node SET agent_status=?, last_heartbeat=? WHERE id=?",
                    scenario[0], LocalDateTime.now(ZoneOffset.UTC).plusSeconds(Long.parseLong(scenario[1])), serverId);
            mockMvc.perform(get("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.agentStatus", is(scenario[2])));
        }
        jdbcTemplate.update("UPDATE server_node SET last_heartbeat=NULL WHERE id=?", serverId);
        mockMvc.perform(get("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agentStatus", is("NOT_CONFIGURED")));
        org.junit.jupiter.api.Assertions.assertEquals("initial", jdbcTemplate.queryForObject(
                "SELECT revision FROM platform_setup WHERE id=1", String.class), "Read-only status must not change configuration");
    }

    @Test
    void setupRequiresClockReviewForFutureProviderVerification() throws Exception {
        Fixture fixture = createApplication();
        jdbcTemplate.update("UPDATE platform_setup SET provider_url=?, provider_token_cipher=?, provider_verified_at=?, provider_error=NULL WHERE id=1",
                "https://synthetic.invalid", settingCipher.encrypt("synthetic-setup-only"), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        mockMvc.perform(get("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerStatus", is("MANUAL_REQUIRED")))
                .andExpect(jsonPath("$.data.providerApiToken").doesNotExist());
        jdbcTemplate.update("UPDATE platform_setup SET provider_verified_at=? WHERE id=1", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(16));
        mockMvc.perform(get("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerStatus", is("SAVED_UNVERIFIED")));
        jdbcTemplate.update("UPDATE platform_setup SET provider_verified_at=? WHERE id=1", LocalDateTime.now(ZoneOffset.UTC));
        mockMvc.perform(get("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerStatus", is("VERIFIED")));
        org.mockito.Mockito.verifyNoInteractions(onboardingProviders);
    }

    @Test
    void nonAdministratorsCannotReadSetupOrRefreshOnboardingCredentials() throws Exception {
        Fixture fixture = createApplication();
        for (String role : java.util.List.of("DEVELOPER", "VIEWER")) {
            String username = role.toLowerCase();
            mockMvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content("""
                      {"username":"%s","displayName":"Role check","email":"",
                       "role":"%s","password":"RoleCheck-2026!","confirmPassword":"RoleCheck-2026!"}
                      """.formatted(username, role))).andExpect(status().isOk());
            var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + username + "\",\"password\":\"RoleCheck-2026!\"}"))
                    .andExpect(status().isOk()).andReturn();
            String token = data(login).path("accessToken").asText();
            mockMvc.perform(get("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isForbidden());
            mockMvc.perform(put("/api/setup").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":\"initial\",\"publicUrl\":\"https://ops.example\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/setup/verify-provider").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/servers/1/registration").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/servers/1/registration").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                      {"requestId":"12345678-1234-1234-1234-123456789012","expectedRevision":"none","confirmed":true}
                      """)).andExpect(status().isForbidden());
            mockMvc.perform(put("/api/cicd/onboarding/{id}/credentials", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"repositoryToken\":\"must-not-be-used\"}")).andExpect(status().isForbidden());
            if ("VIEWER".equals(role)) {
                mockMvc.perform(post("/api/cicd/applications/{id}/builds/1/approval", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                          {"requestId":"12345678-1234-1234-1234-123456789012","commitSha":"abcdef0","imageUri":"unused","confirmed":true,"expectedFingerprint":"0000000000000000000000000000000000000000000000000000000000000000"}
                          """)).andExpect(status().isForbidden());
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals("initial", jdbcTemplate.queryForObject("SELECT revision FROM platform_setup", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='RENEW_AGENT_TOKEN' AND result='FAILED' AND resource_id='1' AND server_id IS NULL", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_release_approval", Integer.class));
        verify(onboardingProviders, times(0)).discover(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentServerCreationReplaysOneEncryptedResponseAndExpiresWithoutDuplicates() throws Exception {
        Fixture fixture = createApplication();
        String key = java.util.UUID.randomUUID().toString();
        String request = "{\"name\":\"retry-safe-server\",\"requestId\":\"" + key + "\"}";
        java.util.function.Supplier<JsonNode> create = () -> {
            try {
                return data(mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                        .andExpect(status().isOk()).andReturn());
            } catch (Exception error) { throw new RuntimeException("Creation test failed", error); }
        };
        var first = java.util.concurrent.CompletableFuture.supplyAsync(create);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(create);
        JsonNode a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
        JsonNode b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertEquals(a.path("server").path("id"), b.path("server").path("id"));
        org.junit.jupiter.api.Assertions.assertTrue(a.path("agentToken").asText().equals(b.path("agentToken").asText()));
        org.junit.jupiter.api.Assertions.assertTrue(a.path("installCommand").asText().equals(b.path("installCommand").asText()));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM server_node WHERE name='retry-safe-server'", Integer.class));
        String encrypted = jdbcTemplate.queryForObject("SELECT response_cipher FROM server_creation_request WHERE request_id=?", String.class, key);
        org.junit.jupiter.api.Assertions.assertTrue(encrypted.startsWith("v1:") && !encrypted.contains(a.path("agentToken").asText()));
        mockMvc.perform(get("/api/servers/creation-requests/{key}", key).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("AVAILABLE")))
                .andExpect(jsonPath("$.data.serverId", is(a.path("server").path("id").asText())))
                .andExpect(jsonPath("$.data.agentToken").doesNotExist()).andExpect(jsonPath("$.data.installCommand").doesNotExist());
        mockMvc.perform(post("/api/servers").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("retry-safe-server", "different-name")))
                .andExpect(status().isConflict());
        jdbcTemplate.update("UPDATE server_creation_request SET expires_at=? WHERE request_id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), key);
        mockMvc.perform(get("/api/servers/creation-requests/{key}", key).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("EXPIRED")));
        // Expiry must be enforced on reads, even before the scheduled cleanup runs.
        mockMvc.perform(post("/api/servers").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isConflict());
        serverNodes.clearExpiredCreationSecrets();
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject("SELECT response_cipher FROM server_creation_request WHERE request_id=?", String.class, key));
        mockMvc.perform(post("/api/servers").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM server_node WHERE name='retry-safe-server'", Integer.class));
    }

    @Test
    void revokedAndDeletedServerCreationCannotReplayCredentialsOrRecreateResources() throws Exception {
        Fixture fixture = createApplication();
        String key = java.util.UUID.randomUUID().toString();
        String request = "{\"name\":\"revocation-check\",\"requestId\":\"" + key + "\"}";
        var created = mockMvc.perform(post("/api/servers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode response = data(created);
        long serverId = response.path("server").path("id").asLong();
        String credential = response.path("agentToken").asText();
        jdbcTemplate.update("UPDATE agent_token SET status='REVOKED', revoked_at=? WHERE server_id=?",
                LocalDateTime.now(ZoneOffset.UTC), serverId);
        var revoked = mockMvc.perform(post("/api/servers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.agentToken").doesNotExist()).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(revoked.getResponse().getContentAsString().contains(credential));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/servers/{id}", serverId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT response_cipher FROM server_creation_request WHERE request_id=?", String.class, key));
        mockMvc.perform(get("/api/servers/creation-requests/{key}", key).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("DELETED")))
                .andExpect(jsonPath("$.data.serverId", is(Long.toString(serverId))));
        mockMvc.perform(post("/api/servers").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM server_node WHERE name='revocation-check' AND deleted=1", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_token WHERE server_id=?", Integer.class, serverId));
    }

    @Test
    void serverCreationKeysAreScopedToTheAuthenticatedAdministrator() throws Exception {
        Fixture fixture = createApplication();
        mockMvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"username":"second-admin","displayName":"Second admin","email":"","role":"ADMIN",
                   "password":"RoleCheck-2026!","confirmPassword":"RoleCheck-2026!"}
                  """)).andExpect(status().isOk());
        String secondToken = data(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"second-admin\",\"password\":\"RoleCheck-2026!\"}"))
                .andExpect(status().isOk()).andReturn()).path("accessToken").asText();
        String key = java.util.UUID.randomUUID().toString();
        String request = "{\"name\":\"owner-isolation\",\"requestId\":\"" + key + "\"}";
        JsonNode first = data(mockMvc.perform(post("/api/servers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn());
        mockMvc.perform(get("/api/servers/creation-requests/{key}", key).header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isNotFound());
        JsonNode second = data(mockMvc.perform(post("/api/servers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken)
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.path("server").path("id"), second.path("server").path("id"));
        org.junit.jupiter.api.Assertions.assertFalse(first.path("agentToken").asText().equals(second.path("agentToken").asText()));
        mockMvc.perform(get("/api/servers/creation-requests/{key}", key).header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serverId", is(second.path("server").path("id").asText())));
        JsonNode replay = data(mockMvc.perform(post("/api/servers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn());
        org.junit.jupiter.api.Assertions.assertEquals(first.path("server").path("id"), replay.path("server").path("id"));
        org.junit.jupiter.api.Assertions.assertTrue(first.path("agentToken").asText().equals(replay.path("agentToken").asText()));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM server_creation_request WHERE request_id=?", Integer.class, key));
    }

    @Test
    void agentTokenRenewalRequiresConfirmationAndReplaysWithoutRotatingAgain() throws Exception {
        Fixture fixture = createApplication();
        JsonNode created = data(mockMvc.perform(post("/api/servers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renewal-test\"}"))
                .andExpect(status().isOk()).andReturn());
        String id = created.path("server").path("id").asText();
        String endpoint = "/api/servers/" + id + "/registration";
        mockMvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        String revision = data(mockMvc.perform(get(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agentToken").doesNotExist())
                .andReturn()).path("revision").asText();
        String key = java.util.UUID.randomUUID().toString();
        String request = objectMapper.writeValueAsString(Map.of("requestId", key, "expectedRevision", revision, "confirmed", true));
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("true", "false")))
                .andExpect(status().isBadRequest());
        java.util.function.Supplier<JsonNode> renew = () -> {
            try {
                return data(mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn());
            } catch (Exception error) { throw new RuntimeException("Renewal request failed", error); }
        };
        var first = java.util.concurrent.CompletableFuture.supplyAsync(renew);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(renew);
        JsonNode a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
        JsonNode b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertTrue(a.path("agentToken").asText().equals(b.path("agentToken").asText()));
        org.junit.jupiter.api.Assertions.assertFalse(created.path("agentToken").asText().equals(a.path("agentToken").asText()));
        String registration = """
          {"token":"%s","hostname":"renewed-host","ip":"10.0.0.40","os":"Linux","kernel":"6.8",
           "arch":"amd64","agentVersion":"0.1.0","cpuModel":"CPU","cpuCores":2,"memoryTotal":4000000000,"diskTotal":50000000000}
          """;
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration.formatted(created.path("agentToken").asText()))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration.formatted(a.path("agentToken").asText())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serverId", is(id)));
        mockMvc.perform(post("/api/agent/heartbeat").header("X-DevPilot-Agent-Token", a.path("agentToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serverId", is(id)));
        mockMvc.perform(post("/api/agent/heartbeat").header("X-DevPilot-Agent-Token", created.path("agentToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_token WHERE server_id=?", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_token WHERE server_id=? AND revoked_at IS NULL", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals("REVOKED", jdbcTemplate.queryForObject("SELECT status FROM agent_token WHERE id=?", String.class, revision));
        String encrypted = jdbcTemplate.queryForObject("SELECT response_cipher FROM agent_token_renewal_request WHERE request_id=?", String.class, key);
        org.junit.jupiter.api.Assertions.assertTrue(encrypted.startsWith("v1:") && !encrypted.contains(a.path("agentToken").asText()));
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace(key, java.util.UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        jdbcTemplate.update("UPDATE agent_token_renewal_request SET expires_at=? WHERE request_id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), key);
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isConflict());
        serverNodes.clearExpiredCreationSecrets();
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject("SELECT response_cipher FROM agent_token_renewal_request WHERE request_id=?", String.class, key));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_token WHERE server_id=?", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='RENEW_AGENT_TOKEN'", Integer.class) > 0);
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
                .andExpect(jsonPath("$.data.checks.length()", is(12)))
                .andExpect(jsonPath("$.data.checks[0].code", is("CONFIGURATION")))
                .andExpect(jsonPath("$.data.checks[0].status", is("BLOCK")))
                .andExpect(jsonPath("$.data.checks[0].action", is("CONFIGURE_CICD")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='AGENT')].status", org.hamcrest.Matchers.contains("PASS")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='BUILD_CALLBACK')].status", org.hamcrest.Matchers.contains("WARN")));
    }

    @Test
    void releasePreflightDoesNotTrustStaleOrFutureRuntimeAndHealthEvidence() throws Exception {
        Fixture fixture = createApplication();
        reportHealth(fixture, "HEALTHY");
        LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
        for (LocalDateTime invalid : java.util.List.of(timestamp.minusMinutes(5), timestamp.plusMinutes(5))) {
            jdbcTemplate.update("UPDATE docker_container_snapshot SET last_seen_at=?", invalid);
            mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.checks[?(@.code=='RUNTIME')].status", org.hamcrest.Matchers.contains("WARN")));
        }
        jdbcTemplate.update("UPDATE docker_container_snapshot SET last_seen_at=?", timestamp);
        jdbcTemplate.update("UPDATE application SET health_checked_at=? WHERE id=?", timestamp.plusMinutes(5), fixture.applicationId());
        mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checks[?(@.code=='HEALTH')].status", org.hamcrest.Matchers.contains("WARN")));
        jdbcTemplate.update("UPDATE application SET health_checked_at=? WHERE id=?", timestamp, fixture.applicationId());
        for (LocalDateTime invalid : java.util.List.of(timestamp.minusMinutes(5), timestamp.plusMinutes(5))) {
            jdbcTemplate.update("UPDATE server_node SET last_heartbeat=?", invalid);
            mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.checks[?(@.code=='AGENT')].status", org.hamcrest.Matchers.contains("WARN")))
                    .andExpect(jsonPath("$.data.checks[?(@.code=='HEALTH')].status", org.hamcrest.Matchers.contains("WARN")))
                    .andExpect(jsonPath("$.data.checks[?(@.code=='RUNTIME')].status", org.hamcrest.Matchers.contains("WARN")));
        }
        jdbcTemplate.update("UPDATE server_node SET last_heartbeat=?", timestamp);
        mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checks[?(@.code=='AGENT')].status", org.hamcrest.Matchers.contains("PASS")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='HEALTH')].status", org.hamcrest.Matchers.contains("PASS")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='RUNTIME')].status", org.hamcrest.Matchers.contains("PASS")));
        jdbcTemplate.update("UPDATE server_node SET agent_status='OFFLINE'");
        mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checks[?(@.code=='AGENT')].status", org.hamcrest.Matchers.contains("BLOCK")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='HEALTH')].status", org.hamcrest.Matchers.contains("WARN")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='RUNTIME')].status", org.hamcrest.Matchers.contains("WARN")));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
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
                .andExpect(jsonPath("$.data.warningCount", is(4)))
                .andExpect(jsonPath("$.data.checks[?(@.code=='HEALTH')].status", org.hamcrest.Matchers.contains("PASS")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='CAPACITY')].status", org.hamcrest.Matchers.contains("PASS")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='ARTIFACT')].status", org.hamcrest.Matchers.contains("WARN")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='BUILD_CALLBACK')].status", org.hamcrest.Matchers.contains("WARN")));
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
                "ghcr.io/acme/demo@sha256:" + "e".repeat(64));

        verify(deploymentWebhookClient).syncCoolifyEnvironment("https://coolify.example/api/v1", "provider-token", "app_42",
                Map.of("API_KEY", new EnvironmentVariable("secret-value", true),
                        "NODE_ENV", new EnvironmentVariable("production", false)), Set.of());
        verify(deploymentWebhookClient).deploy("COOLIFY", "API", null,
                "https://coolify.example/api/v1", "provider-token", "app_42",
                "ghcr.io/acme/demo@sha256:" + "e".repeat(64));
        mockMvc.perform(get("/api/cicd/applications/{id}/environment", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus", is("SYNCED")))
                .andExpect(jsonPath("$.data.syncedRevision", is(1)));
    }

    @Test
    void manualApprovalBindsAuthenticatedUserAndImmutableBuildWithoutDeploying() throws Exception {
        Fixture fixture = createApplication();
        String releaseKey = data(configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", true))
                .path("oneTimeCallbackSecret").asText();
        String buildKey = com.devpilot.server.cicd.service.BuildStatusKey.derive(releaseKey);
        String commit = "c".repeat(40), image = "ghcr.io/acme/demo@sha256:" + "d".repeat(64);
        String running = callback("build:github-77-1", commit, "RUNNING", "PENDING", "PENDING", null);
        JsonNode build = data(mockMvc.perform(post("/api/cicd/webhooks/demo/builds")
                .contentType(MediaType.APPLICATION_JSON).header("X-DevPilot-Signature", sign(buildKey, running)).content(running))
                .andExpect(status().isOk()).andReturn());
        String endpoint = "/api/cicd/applications/" + fixture.applicationId() + "/builds/" + build.path("id").asText() + "/approval";
        String requestId = java.util.UUID.randomUUID().toString();
        String fingerprint = approvalFingerprint(fixture.applicationId(), build.path("id").asText());
        String request = objectMapper.writeValueAsString(Map.of("requestId", requestId, "commitSha", commit, "imageUri", image, "confirmed", true, "expectedFingerprint", fingerprint));
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isBadRequest());
        String success = callback("build:github-77-1", commit, "SUCCEEDED", "PASSED", "PASSED", image);
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(buildKey, success)).content(success)).andExpect(status().isOk());
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace(fingerprint, "0".repeat(64))))
                .andExpect(status().isConflict());
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("true", "false"))).andExpect(status().isBadRequest());
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace(image, image.replace("d".repeat(64), "e".repeat(64)))))
                .andExpect(status().isBadRequest());
        java.util.function.Supplier<JsonNode> approve = () -> {
            try {
                return data(mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn());
            } catch (Exception cause) { throw new RuntimeException("Approval request failed", cause); }
        };
        var first = java.util.concurrent.CompletableFuture.supplyAsync(approve);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(approve);
        JsonNode a = first.get(15, java.util.concurrent.TimeUnit.SECONDS), b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertEquals(a, b);
        org.junit.jupiter.api.Assertions.assertEquals("admin", a.path("approvedUsername").asText());
        org.junit.jupiter.api.Assertions.assertEquals(image, a.path("imageUri").asText());
        org.junit.jupiter.api.Assertions.assertEquals("PRODUCTION", a.path("environment").asText());
        org.junit.jupiter.api.Assertions.assertFalse(a.path("approvedAt").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_release_approval", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace(commit, "f".repeat(40))))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/cicd/applications/{id}/release-approvals", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id", is(a.path("id").asText())));
        org.junit.jupiter.api.Assertions.assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='APPROVE_BUILD_RELEASE' AND result='SUCCESS'", Integer.class) > 0);
        Long appId = Long.valueOf(fixture.applicationId());
        String approvalId = a.path("id").asText();
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.consume(appId, approvalId, "build:github-77-1", "release-1", commit, image + "wrong"));
        // A target change within the same timestamp still invalidates the fingerprint.
        jdbcTemplate.update("UPDATE cicd_configuration SET health_timeout_seconds=health_timeout_seconds+1 WHERE application_id=?", appId);
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.consume(appId, approvalId, "build:github-77-1", "release-1", commit, image));
        jdbcTemplate.update("UPDATE cicd_configuration SET health_timeout_seconds=health_timeout_seconds-1 WHERE application_id=?", appId);
        jdbcTemplate.update("UPDATE cicd_release_approval SET expires_at=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), approvalId);
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.consume(appId, approvalId, "build:github-77-1", "release-1", commit, image));
        java.util.function.Supplier<String> freshApproval = () -> {
            try {
                return data(mockMvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(request.replace(requestId, java.util.UUID.randomUUID().toString())
                                .replace(fingerprint, approvalFingerprint(fixture.applicationId(), build.path("id").asText()))))
                        .andExpect(status().isOk()).andReturn()).path("id").asText();
            } catch (Exception cause) { throw new RuntimeException("Approval fixture failed", cause); }
        };
        String revoked = freshApproval.get();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/cicd/applications/{id}/release-approvals/{approval}", appId, revoked)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revokedAt").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.consume(appId, revoked, "build:github-77-1", "release-1", commit, image));
        String usable = freshApproval.get();
        var consumedFirst = java.util.concurrent.CompletableFuture.supplyAsync(() -> manualApprovals.consume(appId, usable, "build:github-77-1", "release-1", commit, image));
        var consumedSecond = java.util.concurrent.CompletableFuture.supplyAsync(() -> manualApprovals.consume(appId, usable, "build:github-77-1", "release-1", commit, image));
        var consumed = consumedFirst.get(15, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertEquals(consumed, consumedSecond.get(15, java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertEquals("release-1", consumed.consumedByRunId());
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.consume(appId, usable, "build:github-77-1", "release-2", commit, image));
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.revoke(appId, usable));
        String beforeEnvironmentChange = freshApproval.get();
        mockMvc.perform(put("/api/cicd/applications/{id}/environment", appId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0,\"variables\":[{\"key\":\"RELEASE_MODE\",\"value\":\"changed\",\"secret\":false}]}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.consume(appId, beforeEnvironmentChange, "build:github-77-1", "release-env", commit, image));
        org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.server.exception.BusinessException.class,
                () -> manualApprovals.consume(appId, usable, "build:github-77-1", "release-1", commit, image));
        String raced = freshApproval.get();
        java.util.function.Function<String, Boolean> compete = run -> {
            try { manualApprovals.consume(appId, raced, "build:github-77-1", run, commit, image); return true; }
            catch (com.devpilot.server.exception.BusinessException expected) { return false; }
        };
        var raceA = java.util.concurrent.CompletableFuture.supplyAsync(() -> compete.apply("release-race-a"));
        var raceB = java.util.concurrent.CompletableFuture.supplyAsync(() -> compete.apply("release-race-b"));
        org.junit.jupiter.api.Assertions.assertNotEquals(raceA.get(15, java.util.concurrent.TimeUnit.SECONDS), raceB.get(15, java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        var release = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
                callback("release-gated-77", commit, "SUCCEEDED", "PASSED", "PASSED", image));
        release.put("buildExternalRunId", "build:github-77-1");
        release.put("approvalActor", "ci-initiator");
        release.put("approvedAt", Instant.now().toString());
        String unsignedApproval = release.toString();
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(releaseKey, unsignedApproval)).content(unsignedApproval))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deployStatus", is("AWAITING_APPROVAL")));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        String forRelease = freshApproval.get();
        release.put("manualApprovalId", forRelease);
        String approvedRelease = release.toString();
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(releaseKey, approvedRelease)).content(approvedRelease))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(forRelease, jdbcTemplate.queryForObject(
                "SELECT manual_approval_id FROM cicd_pipeline_run WHERE external_run_id='release-gated-77'", String.class));
    }

    @Test
    void callbackReadinessSeparatesBuildAndReleaseEvidenceAndRotationInvalidatesBoth() throws Exception {
        Fixture fixture = createApplication();
        String config = """
          {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo","branchName":"main",
           "deploymentProvider":"COOLIFY","deploymentWebhookUrl":"https://coolify.example/deploy",
           "autoDeploy":true,"productionApproval":true,"rotateCallbackSecret":false}
          """;
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(config)).andExpect(status().isOk()).andReturn();
        String releaseKey = data(configured).path("oneTimeCallbackSecret").asText();
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(releaseKey);
        String running = callback("build:github-evidence-1", "a".repeat(40), "RUNNING", "PENDING", "PENDING", null);
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(releaseKey, running)).content(running)).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT build_callback_verified_at FROM cicd_configuration", java.sql.Timestamp.class));
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, running)).content(running)).andExpect(status().isOk());
        mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checks[?(@.code=='BUILD_CALLBACK')].status", org.hamcrest.Matchers.contains("PASS")))
                .andExpect(jsonPath("$.data.checks[?(@.code=='CALLBACK')].status", org.hamcrest.Matchers.contains("WARN")));
        String releaseRunning = callback("release-evidence-1", "a".repeat(40), "RUNNING", "PENDING", "PENDING", null);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(releaseKey, releaseRunning)).content(releaseRunning)).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertNotNull(jdbcTemplate.queryForObject(
                "SELECT callback_verified_at FROM cicd_configuration", java.sql.Timestamp.class));
        // An ordinary save must preserve evidence; key rotation must not borrow old proof.
        for (boolean rotate : java.util.List.of(false, true)) {
            mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content(config.replace("\"rotateCallbackSecret\":false", "\"rotateCallbackSecret\":" + rotate)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/cicd/applications/{id}/readiness", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.checks[?(@.code=='BUILD_CALLBACK')].status", org.hamcrest.Matchers.contains(rotate ? "WARN" : "PASS")))
                    .andExpect(jsonPath("$.data.checks[?(@.code=='CALLBACK')].status", org.hamcrest.Matchers.contains(rotate ? "WARN" : "PASS")));
        }
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, running)).content(running)).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT build_callback_verified_at FROM cicd_configuration", java.sql.Timestamp.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
    }

    @Test
    void buildOnlyKeyCannotDeployAndLateEventsCannotRegressBuild() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = mockMvc.perform(put("/api/cicd/configurations/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"repositoryProvider":"GITHUB","repositoryUrl":"https://github.com/acme/demo","branchName":"main",
                   "deploymentProvider":"COOLIFY","deploymentWebhookUrl":"https://coolify.example/deploy",
                   "autoDeploy":true,"productionApproval":true,"rotateCallbackSecret":false}
                  """)) .andExpect(status().isOk()).andReturn();
        String releaseKey = data(configured).path("oneTimeCallbackSecret").asText();
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(releaseKey);
        String sha = "a".repeat(40);
        String running = callback("build:github-42-1", sha, "RUNNING", "PENDING", "PENDING", null);
        String success = callback("build:github-42-1", sha, "SUCCEEDED", "PASSED", "PASSED", "ghcr.io/acme/demo@sha256:" + "b".repeat(64));
        for (String url : java.util.List.of("javascript:alert(1)", "data:text/html,test", "file:///etc/passwd", "https://user:secret@example.com")) {
            var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(running);
            invalid.put("runUrl", url);
            String body = invalid.toString();
            mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                    .header("X-DevPilot-Signature", sign(key, body)).content(body)).andExpect(status().isBadRequest());
        }
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT build_callback_verified_at FROM cicd_configuration", java.sql.Timestamp.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_pipeline_run", Integer.class));
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, running)).content(running))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deployStatus", is("BUILDING")));
        jdbcTemplate.update("UPDATE cicd_pipeline_run SET updated_at=? WHERE external_run_id='build:github-42-1'",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(3));
        mockMvc.perform(get("/api/cicd/applications/{id}/runs", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].observationStatus", is("STALE")))
                .andExpect(jsonPath("$.data[0].status", is("RUNNING")))
                .andExpect(jsonPath("$.data[0].observationMessage").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery", Integer.class));
        for (String body : java.util.List.of(success, running, success)) {
            mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                    .header("X-DevPilot-Signature", sign(key, body)).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.deployStatus", is("AWAITING_APPROVAL")))
                    .andExpect(jsonPath("$.data.observationStatus", is("TERMINAL_REPORTED")));
        }
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, success)).content(success)).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_pipeline_run", Integer.class));
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject("SELECT callback_verified_at FROM cicd_configuration", java.sql.Timestamp.class));
        String cancelled = callback("build:github-cancel-1", sha, "CANCELLED", "SKIPPED", "PASSED", null);
        String lateRunning = callback("build:github-cancel-1", sha, "RUNNING", "PENDING", "PENDING", null);
        for (String body : java.util.List.of(cancelled, lateRunning, cancelled)) {
            mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                    .header("X-DevPilot-Signature", sign(key, body)).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("CANCELLED")))
                    .andExpect(jsonPath("$.data.testStatus", is("SKIPPED")))
                    .andExpect(jsonPath("$.data.observationStatus", is("TERMINAL_REPORTED")))
                    .andExpect(jsonPath("$.data.deployStatus", is("BUILD_FAILED")));
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        mockMvc.perform(post("/api/automation/webhooks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"name":"Build failures","endpointUrl":"https://notify.example/hook","eventTypes":["BUILD_FAILED"]}
                  """)) .andExpect(status().isOk());
        String failure = callback("build:github-43-1", sha, "FAILED", "FAILED", "PENDING", null);
        for (int repeat = 0; repeat < 3; repeat++) {
            mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                    .header("X-DevPilot-Signature", sign(key, failure)).content(failure))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.deployStatus", is("BUILD_FAILED")));
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_webhook_delivery WHERE event_type='BUILD_FAILED'", Integer.class));
        var release = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(success);
        release.put("externalRunId", "github-release-99-1");
        release.put("buildExternalRunId", "build:github-42-1");
        release.put("approvalActor", "release-operator");
        release.put("approvedAt", Instant.now().toString());
        release.put("imageUri", "ghcr.io/acme/demo@sha256:" + "c".repeat(64));
        String mismatched = release.toString();
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(releaseKey, mismatched)).content(mismatched))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        release.put("imageUri", "ghcr.io/acme/demo@sha256:" + "b".repeat(64));
        Long buildId = jdbcTemplate.queryForObject("SELECT id FROM cicd_pipeline_run WHERE external_run_id='build:github-42-1'", Long.class);
        JsonNode confirmation = data(mockMvc.perform(post("/api/cicd/applications/{id}/builds/{buildId}/approval", fixture.applicationId(), buildId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", java.util.UUID.randomUUID().toString(), "commitSha", sha,
                        "imageUri", release.path("imageUri").asText(), "confirmed", true,
                        "expectedFingerprint", approvalFingerprint(fixture.applicationId(), buildId.toString())))))
                .andExpect(status().isOk()).andReturn());
        release.put("manualApprovalId", confirmation.path("id").asText());
        String linked = release.toString();
        for (int repeat = 0; repeat < 2; repeat++) {
            mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                    .header("X-DevPilot-Signature", sign(releaseKey, linked)).content(linked))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.buildExternalRunId", is("build:github-42-1")))
                    .andExpect(jsonPath("$.data.manualApprovalId", is(confirmation.path("id").asText())))
                    .andExpect(jsonPath("$.data.approvalActor", is("release-operator")));
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        release.put("approvalActor", "another-operator");
        String modified = release.toString();
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(releaseKey, modified)).content(modified))
                .andExpect(status().isConflict());
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

        String releasedImage = "ghcr.io/acme/demo@sha256:" + "a".repeat(64);
        String success = approvedCallback(secret, "run-42", "abcdef1234567890abcdef1234567890abcdef12", releasedImage);
        for (int request = 0; request < 2; request++) {
            mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                            .header("X-DevPilot-Signature", sign(secret, success)).content(success))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status", is("SUCCEEDED")))
                    .andExpect(jsonPath("$.data.deployStatus", is("TRIGGERED")));
        }
        verify(deploymentWebhookClient, times(1)).deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/api/v1/deploy?uuid=demo", null, null, null,
                releasedImage);

        mockMvc.perform(get("/api/cicd/applications/{id}/runs", fixture.applicationId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].externalRunId", org.hamcrest.Matchers.hasItem("run-42")))
                .andExpect(jsonPath("$.data[*].imageUri", org.hamcrest.Matchers.hasItem(releasedImage)));

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
                .andExpect(jsonPath("$.data[0].imageUri", is(releasedImage)))
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
        String imageA = "ghcr.io/acme/demo@sha256:" + "a".repeat(64);
        String imageB = "ghcr.io/acme/demo@sha256:" + "b".repeat(64);

        submitSuccessfulCallback(secret, "run-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", imageA);
        String queued = approvedCallback(secret, "run-b", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", imageB);
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

        String image = "ghcr.io/acme/demo@sha256:" + "c".repeat(64);
        String body = approvedCallback(secret, "run-disk", "cccccccccccccccccccccccccccccccccccccccc", image);
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
    void queuedReleaseWithExpiredApprovalStopsUntilExplicitReconfirmation() throws Exception {
        Fixture fixture = createApplication();
        String secret = data(configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", true))
                .path("oneTimeCallbackSecret").asText();
        jdbcTemplate.update("UPDATE server_node SET agent_status='OFFLINE'");
        String sha = "f".repeat(40), image = "ghcr.io/acme/demo@sha256:" + "f".repeat(64);
        String body = approvedCallback(secret, "queued-expiry", sha, image);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(secret, body)).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deployStatus", is("QUEUED")));
        String approvalId = objectMapper.readTree(body).path("manualApprovalId").asText();
        jdbcTemplate.update("UPDATE cicd_release_approval SET expires_at=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), approvalId);
        jdbcTemplate.update("UPDATE server_node SET agent_status='ONLINE',last_heartbeat=?", LocalDateTime.now(ZoneOffset.UTC));
        cicdDeploymentService.reconcileQueued();
        org.junit.jupiter.api.Assertions.assertEquals("AWAITING_APPROVAL", jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM cicd_pipeline_run WHERE external_run_id='queued-expiry'", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject("SELECT consumed_at FROM cicd_release_approval WHERE id=?", LocalDateTime.class, approvalId));
        String renewed = approvedCallback(secret, "queued-expiry", sha, image);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(secret, renewed)).content(renewed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deployStatus", is("TRIGGERED")));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
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
        String image = "ghcr.io/acme/demo@sha256:" + "d".repeat(64);
        String body = approvedCallback(secret, "run-offline", "dddddddddddddddddddddddddddddddddddddddd", image);

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
        String image = "ghcr.io/acme/demo@sha256:" + "e".repeat(64);
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
        String imageA = "ghcr.io/acme/demo@sha256:" + "1".repeat(64);
        String imageB = "ghcr.io/acme/demo@sha256:" + "2".repeat(64);
        String imageC = "ghcr.io/acme/demo@sha256:" + "3".repeat(64);

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
        org.junit.jupiter.api.Assertions.assertEquals("sha256:111111111111", jdbcTemplate.queryForObject(
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
        String healthyImage = "ghcr.io/acme/demo@sha256:" + "1".repeat(64);
        submitSuccessfulCallback(secret, "healthy-run", "1111111111111111111111111111111111111111", healthyImage);
        reportHealth(fixture, "HEALTHY");
        cicdDeploymentService.reconcileTriggered();
        when(deploymentWebhookClient.deploy("COOLIFY", "WEBHOOK",
                "https://coolify.example/deploy/demo", null, null, null, healthyImage))
                .thenThrow(new IllegalStateException("provider rejected rollback"));
        submitSuccessfulCallback(secret, "failed-run", "2222222222222222222222222222222222222222",
                "ghcr.io/acme/demo@sha256:" + "2".repeat(64));
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
                "app_42", "ghcr.io/acme/demo@sha256:" + "a".repeat(64))).thenReturn("deploy-1");
        submitSuccessfulCallback(data(result).path("oneTimeCallbackSecret").asText(), "new-run",
                "abcdef123456abcdef123456abcdef123456abcdef", "ghcr.io/acme/demo@sha256:" + "a".repeat(64));
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
                "ghcr.io/acme/demo@sha256:" + "a".repeat(64), LocalDateTime.now(ZoneOffset.UTC));
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
                .andExpect(jsonPath("$.data.agentStatus", is("ONLINE")))
                .andExpect(jsonPath("$.data.containerObservedAt").isNotEmpty())
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
    void missingRuntimeEvidenceIsUnknownAndReadsDoNotRewriteApplication() throws Exception {
        Fixture fixture = createApplication();
        var before = jdbcTemplate.queryForMap("SELECT status, updated_at FROM application WHERE id = ?", fixture.applicationId());
        jdbcTemplate.update("UPDATE server_node SET agent_status = 'OFFLINE'");
        mockMvc.perform(get("/api/applications/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("UNKNOWN")))
                .andExpect(jsonPath("$.data.agentStatus", is("OFFLINE")))
                .andExpect(jsonPath("$.data.runtimeObservationMessage").isNotEmpty());
        jdbcTemplate.update("UPDATE server_node SET agent_status = 'ONLINE'");
        jdbcTemplate.update("UPDATE docker_container_snapshot SET last_seen_at = ?", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        mockMvc.perform(get("/api/applications/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("UNKNOWN")))
                .andExpect(jsonPath("$.data.runtimeObservationMessage").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(before,
                jdbcTemplate.queryForMap("SELECT status, updated_at FROM application WHERE id = ?", fixture.applicationId()));
    }

    @Test
    void dashboardAndMetricsDoNotCountMissingEvidenceAsHealthyOrFaulty() throws Exception {
        Fixture fixture = createApplication();
        LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("UPDATE server_node SET agent_status = 'ONLINE'");
        jdbcTemplate.update("UPDATE docker_container_snapshot SET state = 'running', active = 1, health = 'healthy', last_seen_at = ?", timestamp);
        jdbcTemplate.update("UPDATE application SET health_check_url = 'http://127.0.0.1/health', health_status = 'HEALTHY', health_checked_at = ?", timestamp);
        org.junit.jupiter.api.Assertions.assertEquals(1, applications.healthSummary().healthy());
        jdbcTemplate.update("UPDATE server_node SET agent_status = 'OFFLINE'");
        var unknown = applications.healthSummary();
        org.junit.jupiter.api.Assertions.assertEquals(new com.devpilot.server.application.dto.ApplicationHealthSummary(1, 0, 0, 1), unknown);
        mockMvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.applicationHealthy", is(0)))
                .andExpect(jsonPath("$.data.summary.applicationUnhealthy", is(0)))
                .andExpect(jsonPath("$.data.summary.applicationUnknown", is(1)));
        devPilotMetrics.refresh();
        org.junit.jupiter.api.Assertions.assertEquals(0.0, meterRegistry.get("devpilot.applications.healthy").gauge().value());
        org.junit.jupiter.api.Assertions.assertEquals(1.0, meterRegistry.get("devpilot.applications.unknown").gauge().value());
        jdbcTemplate.update("UPDATE server_node SET agent_status = 'ONLINE'");
        jdbcTemplate.update("UPDATE application SET health_status = 'UNHEALTHY', health_checked_at = ?", timestamp.minusMinutes(5));
        org.junit.jupiter.api.Assertions.assertEquals(unknown, applications.healthSummary());
        jdbcTemplate.update("UPDATE application SET health_checked_at = ?", timestamp);
        org.junit.jupiter.api.Assertions.assertEquals(1, applications.healthSummary().unhealthy());
        jdbcTemplate.update("UPDATE docker_container_snapshot SET last_seen_at = ?", timestamp.minusMinutes(5));
        org.junit.jupiter.api.Assertions.assertEquals(unknown, applications.healthSummary());
        jdbcTemplate.update("UPDATE application SET container_snapshot_id = NULL");
        org.junit.jupiter.api.Assertions.assertEquals(unknown, applications.healthSummary());
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
                 "branch":"main","workflowContent":"workflow_dispatch","providerQuotaConfirmed":true,"environmentValues":{"DATABASE_PASSWORD":"runtime-secret"}}
                """;
        mockMvc.perform(post("/api/cicd/onboarding/{id}", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("\"providerQuotaConfirmed\":true", "\"providerQuotaConfirmed\":false")))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_onboarding", Integer.class));
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
        jdbcTemplate.update("UPDATE cicd_onboarding SET credentials_updated_at = ?", LocalDateTime.now(ZoneOffset.UTC).minusHours(25));
        onboardingService.clearExpiredCredentials();
        String expiredPlan = settingCipher.decrypt(jdbcTemplate.queryForObject("SELECT request_cipher FROM cicd_onboarding", String.class));
        org.junit.jupiter.api.Assertions.assertFalse(expiredPlan.contains("repository-secret"));
        org.junit.jupiter.api.Assertions.assertFalse(expiredPlan.contains("provider-secret"));
        org.junit.jupiter.api.Assertions.assertFalse(expiredPlan.contains("runtime-secret"));
        mockMvc.perform(post("/api/cicd/onboarding/{id}/advance", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("EXPIRED")));
        mockMvc.perform(put("/api/cicd/onboarding/{id}/credentials", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/cicd/onboarding/{id}/credentials", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"repositoryToken":"repository-secret","providerApiToken":"provider-secret",
                   "environmentValues":{"DATABASE_PASSWORD":"runtime-secret"},"providerQuotaConfirmed":true}
                  """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("PENDING")))
                .andExpect(jsonPath("$.data.stage", is(0)));
        onboardingService.clearExpiredCredentials();
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT status FROM cicd_onboarding", String.class));
        String ports = "{\"containerPort\":9090,\"hostPort\":18082,\"healthPath\":\"/ready\"}";
        jdbcTemplate.update("UPDATE cicd_onboarding SET lease_until = ?", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        mockMvc.perform(put("/api/cicd/onboarding/{id}/ports", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(ports)).andExpect(status().isConflict());
        jdbcTemplate.update("UPDATE cicd_onboarding SET lease_until = NULL, status = 'FAILED'");
        mockMvc.perform(put("/api/cicd/onboarding/{id}/ports", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(ports.replace("18082", "80"))).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/cicd/onboarding/{id}/ports", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(ports)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hostPort", is(18082)))
                .andExpect(jsonPath("$.data.containerPort", is(9090)))
                .andExpect(jsonPath("$.data.healthPath", is("/ready")))
                .andExpect(jsonPath("$.data.status", is("PENDING")));
        verify(onboardingProviders, times(0)).ensureApplication(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        jdbcTemplate.update("UPDATE server_node SET listening_tcp_ports = '', ports_collected_at = ?, agent_status = 'ONLINE'",
                LocalDateTime.now(ZoneOffset.UTC));
        for (int stage = 0; stage < 3; stage++) {
            mockMvc.perform(post("/api/cicd/onboarding/{id}/advance", fixture.applicationId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk());
        }
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT status FROM cicd_onboarding", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject("SELECT stage FROM cicd_onboarding", Integer.class));
        mockMvc.perform(put("/api/cicd/onboarding/{id}/ports", fixture.applicationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(ports)).andExpect(status().isConflict());
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
        String body = approvedCallback(secret, runId, commitSha, image);
        mockMvc.perform(post("/api/cicd/webhooks/demo").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(secret, body)).content(body))
                .andExpect(status().isOk());
    }

    private String approvalFingerprint(String applicationId, String buildId) throws Exception {
        return data(mockMvc.perform(get("/api/cicd/applications/{id}/builds/{buildId}/approval-context", applicationId, buildId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixtureAdministratorToken))
                .andExpect(status().isOk()).andReturn()).path("fingerprint").asText();
    }

    private String approvedCallback(String secret, String runId, String commitSha, String image) throws Exception {
        String buildExternalId = "build:fixture-" + runId;
        String buildBody = callback(buildExternalId, commitSha, "SUCCEEDED", "PASSED", "PASSED", image);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(secret);
        JsonNode build = data(mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, buildBody)).content(buildBody)).andExpect(status().isOk()).andReturn());
        JsonNode approval = data(mockMvc.perform(post("/api/cicd/applications/{id}/builds/{buildId}/approval",
                build.path("applicationId").asText(), build.path("id").asText())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixtureAdministratorToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", java.util.UUID.randomUUID().toString(),
                        "commitSha", commitSha, "imageUri", image, "confirmed", true,
                        "expectedFingerprint", approvalFingerprint(build.path("applicationId").asText(), build.path("id").asText())))))
                .andExpect(status().isOk()).andReturn());
        var release = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(callback(runId, commitSha, "SUCCEEDED", "PASSED", "PASSED", image));
        release.put("buildExternalRunId", buildExternalId);
        release.put("manualApprovalId", approval.path("id").asText());
        release.put("approvalActor", "fixture-ci-initiator");
        release.put("approvedAt", Instant.now().toString());
        return release.toString();
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

    @Test
    void signedFinalBuildEvidenceCanRepairObservationButNeverOverwriteSignedFinalResult() throws Exception {
        Fixture fixture = createApplication();
        var configured = configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", true);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(data(configured).path("oneTimeCallbackSecret").asText());
        String sha = "a".repeat(40);
        String external = "build:github-987-1";
        String running = callback(external, sha, "RUNNING", "PENDING", "PENDING", null);
        String success = callback(external, sha, "SUCCEEDED", "PASSED", "PASSED", "ghcr.io/acme/demo@sha256:" + "b".repeat(64));
        var created = mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, running)).content(running)).andExpect(status().isOk()).andReturn();
        String id = data(created).path("id").asText();
        String request = objectMapper.writeValueAsString(java.util.Map.of("revision", "initial", "consent", true,
                "repositoryToken", "fixture_observer_token", "expiresAt", java.time.Instant.now().plusSeconds(3600).toString()));
        mockMvc.perform(put("/api/cicd/applications/" + fixture.applicationId() + "/github-observer")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        org.mockito.Mockito.when(githubRunClient.observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.devpilot.server.cicd.service.GithubRunEvidence.State.FAILED);
        githubReconciler.checkApplication(Long.valueOf(fixture.applicationId()));
        org.junit.jupiter.api.Assertions.assertEquals("GITHUB_OBSERVATION", jdbcTemplate.queryForObject(
                "SELECT build_result_source FROM cicd_pipeline_run WHERE id=?", String.class, id));
        // A late start is not repair evidence, and an invalid signature cannot repair anything.
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, running)).content(running))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("FAILED")));
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign("wrong-key", success)).content(success)).andExpect(status().isUnauthorized());
        String changed = callback(external, "c".repeat(40), "SUCCEEDED", "PASSED", "PASSED", "ghcr.io/acme/demo@sha256:" + "b".repeat(64));
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, changed)).content(changed)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, success)).content(success))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deployStatus", is("AWAITING_APPROVAL")));
        org.junit.jupiter.api.Assertions.assertEquals("CALLBACK", jdbcTemplate.queryForObject(
                "SELECT build_result_source FROM cicd_pipeline_run WHERE id=?", String.class, id));
        String failure = callback(external, sha, "FAILED", "FAILED", "PASSED", null);
        for (String body : java.util.List.of(failure, running, success)) {
            mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                    .header("X-DevPilot-Signature", sign(key, body)).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("SUCCEEDED")));
        }
        // A genuine signed failure stays final even if later a valid success report arrives.
        String otherFailure = callback("build:github-988-1", sha, "FAILED", "FAILED", "PASSED", null);
        String otherSuccess = callback("build:github-988-1", sha, "SUCCEEDED", "PASSED", "PASSED", "ghcr.io/acme/demo@sha256:" + "b".repeat(64));
        for (String body : java.util.List.of(otherFailure, otherSuccess)) {
            mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                    .header("X-DevPilot-Signature", sign(key, body)).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("FAILED")));
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_release_approval", Integer.class));
    }

    @Test
    void automaticGithubCheckPreservesSuccessGateAndDropsRevokedResult() throws Exception {
        Fixture fixture = createApplication();
        var configured = configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", true);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(data(configured).path("oneTimeCallbackSecret").asText());
        String sha = "a".repeat(40);
        String body = callback("build:github-42-2", sha, "RUNNING", "PENDING", "PENDING", null);
        var created = mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, body)).content(body)).andExpect(status().isOk()).andReturn();
        String buildId = data(created).path("id").asText();
        String path = "/api/cicd/applications/" + fixture.applicationId() + "/github-observer";
        String request = objectMapper.writeValueAsString(java.util.Map.of("revision", "initial", "consent", true,
                "repositoryToken", "fixture_observer_token", "expiresAt", java.time.Instant.now().plusSeconds(3600).toString()));
        mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        org.mockito.Mockito.when(githubRunClient.observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.devpilot.server.cicd.service.GithubRunEvidence.State.SUCCESS_AWAITING_BUILD_EVIDENCE);
        githubReconciler.checkApplication(Long.valueOf(fixture.applicationId()));
        org.junit.jupiter.api.Assertions.assertEquals("RUNNING", jdbcTemplate.queryForObject("SELECT status FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        org.junit.jupiter.api.Assertions.assertEquals("SUCCESS_AWAITING_BUILD_EVIDENCE", jdbcTemplate.queryForObject("SELECT github_observation FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        mockMvc.perform(get("/api/cicd/applications/" + fixture.applicationId() + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].observationStatus", is("STALE")))
                .andExpect(jsonPath("$.data[0].observationMessage", org.hamcrest.Matchers.containsString("人工确认前不能发布")));
        // A second worker cannot query while the database claim/backoff is active.
        githubReconciler.checkApplication(Long.valueOf(fixture.applicationId()));
        org.mockito.Mockito.verify(githubRunClient, org.mockito.Mockito.times(1)).observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        jdbcTemplate.update("UPDATE github_observer_configuration SET next_check_at=NULL WHERE application_id=?", fixture.applicationId());
        org.mockito.Mockito.when(githubRunClient.observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.devpilot.server.cicd.service.GithubRunEvidence.State.FAILED);
        githubReconciler.checkApplication(Long.valueOf(fixture.applicationId()));
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT status FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT github_observation FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        // Deliver a real signed callback while the remote check is in progress.
        jdbcTemplate.update("UPDATE cicd_pipeline_run SET status='RUNNING' WHERE id=?", buildId);
        jdbcTemplate.update("UPDATE github_observer_configuration SET next_check_at=NULL WHERE application_id=?", fixture.applicationId());
        String cancelledBody = callback("build:github-42-2", sha, "CANCELLED", "SKIPPED", "SKIPPED", null);
        org.mockito.Mockito.when(githubRunClient.observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                            .header("X-DevPilot-Signature", sign(key, cancelledBody)).content(cancelledBody)).andExpect(status().isOk());
                    return com.devpilot.server.cicd.service.GithubRunEvidence.State.FAILED;
                });
        githubReconciler.checkApplication(Long.valueOf(fixture.applicationId()));
        org.junit.jupiter.api.Assertions.assertEquals("CANCELLED", jdbcTemplate.queryForObject("SELECT status FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        // Simulate a separate pending fixture and revocation while remote I/O is in progress.
        jdbcTemplate.update("UPDATE cicd_pipeline_run SET status='RUNNING' WHERE id=?", buildId);
        jdbcTemplate.update("UPDATE github_observer_configuration SET next_check_at=NULL WHERE application_id=?", fixture.applicationId());
        org.mockito.Mockito.doAnswer(invocation -> { jdbcTemplate.update("UPDATE github_observer_configuration SET enabled=0,token_cipher=NULL,revision='revoked-fixture' WHERE application_id=?", fixture.applicationId());
                    return com.devpilot.server.cicd.service.GithubRunEvidence.State.CANCELLED; })
                .when(githubRunClient).observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        githubReconciler.checkApplication(Long.valueOf(fixture.applicationId()));
        org.junit.jupiter.api.Assertions.assertEquals("RUNNING", jdbcTemplate.queryForObject("SELECT status FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
    }

    @Test
    void concurrentGithubObserversClaimOnceAndPreserveCallbackDuringRemoteRead() throws Exception {
        Fixture fixture = createApplication();
        var configured = configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", true);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(data(configured).path("oneTimeCallbackSecret").asText());
        String sha = "b".repeat(40);
        String running = callback("build:github-101-1", sha, "RUNNING", "PENDING", "PENDING", null);
        var created = mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, running)).content(running)).andExpect(status().isOk()).andReturn();
        String buildId = data(created).path("id").asText();
        Long applicationId = Long.valueOf(fixture.applicationId());
        githubObserverConfiguration.save(applicationId,
                new com.devpilot.server.cicd.service.GithubObserverConfigurationService.Request("initial", true,
                        "fixture_concurrent_token", Instant.now().plusSeconds(3600)));
        var enteredRemote = new java.util.concurrent.CountDownLatch(1);
        var releaseRemote = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            enteredRemote.countDown();
            org.junit.jupiter.api.Assertions.assertTrue(releaseRemote.await(15, java.util.concurrent.TimeUnit.SECONDS),
                    "Test must release the remote read");
            return com.devpilot.server.cicd.service.GithubRunEvidence.State.FAILED;
        }).when(githubRunClient).observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> githubReconciler.checkApplication(applicationId));
            org.junit.jupiter.api.Assertions.assertTrue(enteredRemote.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // A second worker must finish while the first remote call is still blocked.
            workers.submit(() -> githubReconciler.checkApplication(applicationId)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            String cancelled = callback("build:github-101-1", sha, "CANCELLED", "SKIPPED", "SKIPPED", null);
            workers.submit(() -> {
                mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(key, cancelled)).content(cancelled)).andExpect(status().isOk());
                return null;
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            releaseRemote.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            org.mockito.Mockito.verify(githubRunClient, times(1)).observe(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
            org.junit.jupiter.api.Assertions.assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                    "SELECT status FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
            org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                    "SELECT github_observation FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        } finally {
            releaseRemote.countDown();
            workers.shutdownNow();
            org.junit.jupiter.api.Assertions.assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void oldOrFutureActiveGithubObservationsAreNotCurrentEvidence() throws Exception {
        Fixture fixture = createApplication();
        var configured = configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", false);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(data(configured).path("oneTimeCallbackSecret").asText());
        String body = callback("build:github-102-1", "c".repeat(40), "RUNNING", "PENDING", "PENDING", null);
        var created = mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, body)).content(body)).andExpect(status().isOk()).andReturn();
        String buildId = data(created).path("id").asText();
        LocalDateTime reference = LocalDateTime.now(ZoneOffset.UTC);
        for (LocalDateTime checked : new LocalDateTime[]{reference.minusMinutes(3), reference.plusMinutes(3)}) {
            jdbcTemplate.update("UPDATE cicd_pipeline_run SET github_observation='ACTIVE',github_checked_at=?,updated_at=? WHERE id=?",
                    checked, reference.minusMinutes(4), buildId);
            mockMvc.perform(get("/api/cicd/applications/" + fixture.applicationId() + "/runs")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].status", is("RUNNING")))
                    .andExpect(jsonPath("$.data[0].observationStatus", is("STALE")))
                    .andExpect(jsonPath("$.data[0].observationMessage", org.hamcrest.Matchers.containsString("请重新核对")));
        }
        jdbcTemplate.update("UPDATE cicd_pipeline_run SET github_checked_at=? WHERE id=?", reference.minusSeconds(10), buildId);
        mockMvc.perform(get("/api/cicd/applications/" + fixture.applicationId() + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].observationStatus", is("LAST_REPORTED")))
                .andExpect(jsonPath("$.data[0].observationMessage", org.hamcrest.Matchers.containsString("不是应用在线证明")));
        org.mockito.Mockito.verifyNoInteractions(githubRunClient);
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
    }

    @Test
    void observerRotationRejectsOldResultAndUsesNewCredentialAfterBackoff() throws Exception {
        Fixture fixture = createApplication();
        var configured = configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", true);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(data(configured).path("oneTimeCallbackSecret").asText());
        String sha = "d".repeat(40);
        String body = callback("build:github-103-1", sha, "RUNNING", "PENDING", "PENDING", null);
        var created = mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, body)).content(body)).andExpect(status().isOk()).andReturn();
        String buildId = data(created).path("id").asText();
        Long appId = Long.valueOf(fixture.applicationId());
        var saved = githubObserverConfiguration.save(appId,
                new com.devpilot.server.cicd.service.GithubObserverConfigurationService.Request("initial", true,
                        "fixture_old_token", Instant.now().plusSeconds(3600)));
        org.mockito.Mockito.doAnswer(invocation -> {
            githubObserverConfiguration.save(appId,
                    new com.devpilot.server.cicd.service.GithubObserverConfigurationService.Request(saved.revision(), true,
                            "fixture_new_token", Instant.now().plusSeconds(3600)));
            return com.devpilot.server.cicd.service.GithubRunEvidence.State.FAILED;
        }).when(githubRunClient).observe("https://github.com/acme/demo", "build:github-103-1", sha, "main", "fixture_old_token");
        githubReconciler.checkApplication(appId);
        org.junit.jupiter.api.Assertions.assertEquals("RUNNING", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT github_observation FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        org.junit.jupiter.api.Assertions.assertEquals("fixture_new_token", settingCipher.decrypt(jdbcTemplate.queryForObject(
                "SELECT token_cipher FROM github_observer_configuration WHERE application_id=?", String.class, appId)));
        org.junit.jupiter.api.Assertions.assertNotEquals(saved.revision(), githubObserverConfiguration.status(appId).revision());
        githubReconciler.checkApplication(appId);
        org.mockito.Mockito.verify(githubRunClient, times(1)).observe("https://github.com/acme/demo", "build:github-103-1", sha, "main", "fixture_old_token");
        org.mockito.Mockito.verifyNoMoreInteractions(githubRunClient);
        // Advance the database deadline, not a wall-clock sleep or an automatic reset on save.
        jdbcTemplate.update("UPDATE github_observer_configuration SET next_check_at=? WHERE application_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), appId);
        when(githubRunClient.observe("https://github.com/acme/demo", "build:github-103-1", sha, "main", "fixture_new_token"))
                .thenReturn(com.devpilot.server.cicd.service.GithubRunEvidence.State.ACTIVE);
        githubReconciler.checkApplication(appId);
        verify(githubRunClient).observe("https://github.com/acme/demo", "build:github-103-1", sha, "main", "fixture_new_token");
        org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT github_observation FROM cicd_pipeline_run WHERE id=?", String.class, buildId));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
    }

    @Test
    void expiredAndChangedObserverConfigurationCannotQueryGithub() throws Exception {
        Fixture fixture = createApplication();
        var configured = configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", false);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(data(configured).path("oneTimeCallbackSecret").asText());
        String running = callback("build:github-99-1", "a".repeat(40), "RUNNING", "PENDING", "PENDING", null);
        mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, running)).content(running)).andExpect(status().isOk());
        Long applicationId = Long.valueOf(fixture.applicationId());
        var request = new com.devpilot.server.cicd.service.GithubObserverConfigurationService.Request("initial", true,
                "fixture_expiring_token", java.time.Instant.now().plusSeconds(3600));
        var saved = githubObserverConfiguration.save(applicationId, request);
        org.junit.jupiter.api.Assertions.assertNull(saved.nextCheckAt());
        Instant due = Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbcTemplate.update("UPDATE github_observer_configuration SET next_check_at=? WHERE application_id=?", java.sql.Timestamp.from(due), applicationId);
        org.junit.jupiter.api.Assertions.assertEquals(due, githubObserverConfiguration.status(applicationId).nextCheckAt());
        jdbcTemplate.update("UPDATE cicd_configuration SET branch_name='another-branch' WHERE application_id=?", applicationId);
        org.junit.jupiter.api.Assertions.assertEquals("CONFIGURATION_CHANGED", githubObserverConfiguration.status(applicationId).state());
        org.junit.jupiter.api.Assertions.assertNull(githubObserverConfiguration.status(applicationId).nextCheckAt());
        jdbcTemplate.update("UPDATE github_observer_configuration SET next_check_at=NULL WHERE application_id=?", applicationId);
        githubReconciler.checkApplication(applicationId);
        org.mockito.Mockito.verifyNoInteractions(githubRunClient);
        jdbcTemplate.update("UPDATE cicd_configuration SET branch_name='main' WHERE application_id=?", applicationId);
        jdbcTemplate.update("UPDATE github_observer_configuration SET expires_at=?,next_check_at=NULL WHERE application_id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), applicationId);
        org.junit.jupiter.api.Assertions.assertEquals("EXPIRED", githubObserverConfiguration.status(applicationId).state());
        githubReconciler.checkApplication(applicationId);
        org.mockito.Mockito.verifyNoInteractions(githubRunClient);
        githubObserverConfiguration.clearExpired();
        org.junit.jupiter.api.Assertions.assertFalse(githubObserverConfiguration.status(applicationId).credentialConfigured());
        org.junit.jupiter.api.Assertions.assertNotEquals(saved.revision(), githubObserverConfiguration.status(applicationId).revision());
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject("SELECT token_cipher FROM github_observer_configuration WHERE application_id=?", String.class, applicationId));
    }

    @Test
    void observerConfigurationRejectsNonAdminsAndMalformedSecretsWithoutDisclosure() throws Exception {
        Fixture fixture = createApplication();
        configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", false);
        String path = "/api/cicd/applications/" + fixture.applicationId() + "/github-observer";
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        String marker = "fixture_sensitive_marker";
        String valid = objectMapper.writeValueAsString(Map.of("revision", "initial", "consent", true,
                "repositoryToken", marker, "expiresAt", Instant.now().plusSeconds(3600).toString()));
        for (String role : new String[]{"VIEWER", "DEVELOPER"}) {
            String username = "observer" + role.toLowerCase();
            String password = "ObserverRole-2026!";
            mockMvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                            "username", username, "displayName", username, "email", "", "role", role,
                            "password", password, "confirmPassword", password)))).andExpect(status().isOk());
            var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                    .andExpect(status().isOk()).andReturn();
            String authorization = "Bearer " + data(login).path("accessToken").asText();
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isForbidden());
            mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                    .param("revision", "initial").header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isForbidden());
        }
        for (String invalid : new String[]{valid.replace(marker, marker + "!"),
                valid.replace("\"" + marker + "\"", "{\"value\":\"" + marker + "\"}"),
                "{\"repositoryToken\":\"" + marker + "\",", valid.replace("\"consent\":true", "\"consent\":false")}) {
            var rejected = mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest()).andReturn();
            org.junit.jupiter.api.Assertions.assertFalse(rejected.getResponse().getContentAsString().contains(marker));
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM github_observer_configuration", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE request_params LIKE ? OR error_message LIKE ?", Integer.class,
                "%" + marker + "%", "%" + marker + "%"));
        org.mockito.Mockito.verifyNoInteractions(githubRunClient);
    }

    @Test
    void observerCredentialsRequireConsentAreEncryptedAndCanBeRemoved() throws Exception {
        Fixture fixture = createApplication();
        configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", false);
        String path = "/api/cicd/applications/" + fixture.applicationId() + "/github-observer";
        String token = "fixture_observer_secret";
        String request = objectMapper.writeValueAsString(java.util.Map.of("revision", "initial", "consent", true,
                "repositoryToken", token, "expiresAt", java.time.Instant.now().plusSeconds(3600).toString()));
        mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("true", "false"))).andExpect(status().isBadRequest());
        var saved = mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("SAVED_UNVERIFIED")))
                .andExpect(jsonPath("$.data.credentialConfigured", is(true))).andReturn();
        String encrypted = jdbcTemplate.queryForObject("SELECT token_cipher FROM github_observer_configuration WHERE application_id=?", String.class, fixture.applicationId());
        org.junit.jupiter.api.Assertions.assertNotEquals(token, encrypted);
        org.junit.jupiter.api.Assertions.assertEquals(token, settingCipher.decrypt(encrypted));
        org.junit.jupiter.api.Assertions.assertFalse(saved.getResponse().getContentAsString().contains(token));
        mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isConflict());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path).param("revision", data(saved).path("revision").asText())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("DISABLED")))
                .andExpect(jsonPath("$.data.credentialConfigured", is(false)));
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject("SELECT token_cipher FROM github_observer_configuration WHERE application_id=?", String.class, fixture.applicationId()));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_params LIKE '%fixture_observer_secret%'", Integer.class));
        org.mockito.Mockito.verifyNoInteractions(githubRunClient);
    }

    @Test
    void githubCheckIsIdentityScopedReadOnlyAndDoesNotPersistToken() throws Exception {
        Fixture fixture = createApplication();
        MvcResult configured = configureWebhook(fixture, fixture.applicationId(), "https://coolify.example/deploy", true);
        String key = com.devpilot.server.cicd.service.BuildStatusKey.derive(data(configured).path("oneTimeCallbackSecret").asText());
        String sha = "a".repeat(40);
        String body = callback("build:github-42-2", sha, "RUNNING", "PENDING", "PENDING", null);
        MvcResult created = mockMvc.perform(post("/api/cicd/webhooks/demo/builds").contentType(MediaType.APPLICATION_JSON)
                .header("X-DevPilot-Signature", sign(key, body)).content(body)).andExpect(status().isOk()).andReturn();
        String buildId = data(created).path("id").asText();
        String request = "{\"repositoryToken\":\"fixture_readonly_token\"}";
        org.mockito.Mockito.when(githubRunClient.observe("https://github.com/acme/demo", "build:github-42-2", sha,
                "main", "fixture_readonly_token")).thenReturn(com.devpilot.server.cicd.service.GithubRunEvidence.State.SUCCESS_AWAITING_BUILD_EVIDENCE);
        String path = "/api/cicd/applications/" + fixture.applicationId() + "/builds/" + buildId + "/github-check";
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"checkviewer","displayName":"Check viewer","email":"","role":"VIEWER",
                 "password":"RoleCheck-2026!","confirmPassword":"RoleCheck-2026!"}
                """)).andExpect(status().isOk());
        var viewerLogin = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"checkviewer\",\"password\":\"RoleCheck-2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + data(viewerLogin).path("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("SUCCESS_AWAITING_BUILD_EVIDENCE")))
                .andExpect(jsonPath("$.data.repositoryToken").doesNotExist());
        mockMvc.perform(post("/api/cicd/applications/0/builds/" + buildId + "/github-check")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isNotFound());
        org.mockito.Mockito.verify(githubRunClient, org.mockito.Mockito.times(1)).observe(
                "https://github.com/acme/demo", "build:github-42-2", sha, "main", "fixture_readonly_token");
        org.junit.jupiter.api.Assertions.assertEquals("RUNNING", jdbcTemplate.queryForObject(
                "SELECT status FROM cicd_pipeline_run WHERE id = ?", String.class, buildId));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cicd_deployment", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE request_params LIKE '%fixture_readonly_token%'", Integer.class));
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
        fixtureAdministratorToken = data(result).path("accessToken").asText();
        return fixtureAdministratorToken;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private record Fixture(String accessToken, String applicationId, String agentToken) {}
}

package com.devpilot.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devpilot.server.alert.service.AlertEvaluationService;
import com.devpilot.server.alert.service.WebhookDeliveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AlertIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AlertEvaluationService evaluationService;
    @Autowired
    private WebhookDeliveryService webhookDeliveryService;
    private HttpServer webhookServer;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM audit_log");
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

    @AfterEach
    void stopWebhook() {
        if (webhookServer != null) {
            webhookServer.stop(0);
        }
    }

    @Test
    void durationLifecycleAcknowledgementAndEncryptedWebhookWorkEndToEnd() throws Exception {
        AtomicReference<String> webhookBody = new AtomicReference<>();
        webhookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhookServer.createContext("/alerts", exchange -> {
            webhookBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        webhookServer.start();
        String webhookUrl = "http://127.0.0.1:" + webhookServer.getAddress().getPort() + "/alerts";

        String accessToken = setupAdministrator();
        mockMvc.perform(put("/api/alerts/webhook")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "url", webhookUrl))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled", is(true)))
                .andExpect(jsonPath("$.data.configured", is(true)))
                .andExpect(jsonPath("$.data.destinationType", is("CUSTOM")));
        String stored = jdbcTemplate.queryForObject(
                "SELECT setting_value FROM system_setting WHERE setting_key = 'ALERT_WEBHOOK_URL'", String.class);
        if (stored == null || !stored.startsWith("v1:") || stored.contains(webhookUrl)) {
            throw new AssertionError("Webhook URL must be encrypted at rest");
        }

        MvcResult serverResult = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"prod-node\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode serverData = data(serverResult);
        String serverId = serverData.path("server").path("id").asText();
        String agentToken = serverData.path("agentToken").asText();
        register(agentToken);
        uploadMetric(agentToken, 95.0);

        MvcResult ruleResult = mockMvc.perform(post("/api/alerts/rules")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Production CPU high", "metricType", "SERVER_CPU", "operator", "GT",
                                "threshold", 90, "durationSeconds", 60, "severity", "CRITICAL",
                                "serverId", serverId, "enabled", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serverName", is("prod-node"))).andReturn();
        String ruleId = data(ruleResult).path("id").asText();

        evaluationService.evaluateAll();
        mockMvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        jdbcTemplate.update("UPDATE alert_condition_state SET first_met_at = ? WHERE rule_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(61), Long.parseLong(ruleId));
        evaluationService.evaluateAll();

        MvcResult firing = mockMvc.perform(get("/api/alerts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].status", is("FIRING")))
                .andExpect(jsonPath("$.data[0].severity", is("CRITICAL")))
                .andExpect(jsonPath("$.data[0].message", containsString("95.00%"))).andReturn();
        String eventId = data(firing).get(0).path("id").asText();

        webhookDeliveryService.deliverPending();
        if (webhookBody.get() == null || !webhookBody.get().contains("Production CPU high")) {
            throw new AssertionError("Expected a generic webhook alert payload");
        }
        mockMvc.perform(post("/api/alerts/{id}/acknowledge", eventId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("ACKNOWLEDGED")))
                .andExpect(jsonPath("$.data.acknowledgedByName", is("Administrator")));

        jdbcTemplate.update("UPDATE server_metric SET cpu_usage = 20 WHERE server_id = ?", Long.parseLong(serverId));
        evaluationService.evaluateAll();
        mockMvc.perform(get("/api/alerts").param("status", "RESOLVED")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].status", is("RESOLVED")))
                .andExpect(jsonPath("$.data[0].resolvedAt").isNotEmpty());
        mockMvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.currentAlerts", is(0)));
    }

    @Test
    void containerRestartStormUsesRollingDeltaAndResolvesAfterWindow() throws Exception {
        String accessToken = setupAdministrator();
        MvcResult serverResult = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"restart-node\"}"))
                .andExpect(status().isOk()).andReturn();
        String serverId = data(serverResult).path("server").path("id").asText();
        String agentToken = data(serverResult).path("agentToken").asText();
        register(agentToken);
        uploadDocker(agentToken, 0);
        uploadDocker(agentToken, 4);

        mockMvc.perform(post("/api/alerts/rules")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Container restart storm", "metricType", "CONTAINER_RESTARTS",
                                "operator", "GTE", "threshold", 3, "durationSeconds", 0,
                                "severity", "CRITICAL", "serverId", serverId, "enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.threshold", is(3.0)));

        evaluationService.evaluateAll();
        mockMvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].resourceName", is("demo")))
                .andExpect(jsonPath("$.data[0].message", containsString("restarted 4 times within 10 minutes")));

        jdbcTemplate.update("UPDATE docker_container_snapshot SET restart_window_started_at = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11));
        evaluationService.evaluateAll();
        mockMvc.perform(get("/api/alerts").param("status", "RESOLVED")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    private void register(String token) throws Exception {
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"token":"%s","hostname":"prod-host","ip":"10.0.0.50","os":"Linux",
                 "kernel":"6.8","arch":"amd64","agentVersion":"0.1.0","cpuModel":"CPU",
                 "cpuCores":4,"memoryTotal":1000,"diskTotal":2000}
                """.formatted(token))).andExpect(status().isOk());
    }

    private void uploadMetric(String token, double cpu) throws Exception {
        String payload = """
                {"agentVersion":"0.1.0","collectedAt":"%s","cpuUsage":%s,
                 "loadOne":0.5,"loadFive":0.4,"loadFifteen":0.3,
                 "memoryTotal":1000,"memoryUsed":500,"memoryAvailable":500,
                 "diskTotal":2000,"diskUsed":500,"diskFree":1500,
                 "networkBytesSent":10000,"networkBytesReceived":20000,
                 "networkUploadRate":120.5,"networkDownloadRate":240.5}
                """.formatted(Instant.now(), cpu);
        mockMvc.perform(post("/api/agent/metrics").header("X-DevPilot-Agent-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
    }

    private void uploadDocker(String token, int restartCount) throws Exception {
        String payload = """
                {"agentVersion":"0.1.0","available":true,"engineVersion":"28.3.3","images":1,
                 "volumes":0,"networks":1,"collectedAt":"%s","containers":[
                  {"containerId":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                   "name":"demo","image":"ghcr.io/acme/demo:sha-test","state":"running","status":"Up",
                   "health":"healthy","cpuUsage":1,"memoryUsage":1,"memoryLimit":2,
                   "networkRx":1,"networkTx":1,"ports":[],"createdAt":"2026-08-30T00:00:00Z",
                   "startedAt":"2026-08-31T00:00:00Z","restartCount":%d,"volumes":[],"environment":[]}]}
                """.formatted(Instant.now(), restartCount);
        mockMvc.perform(post("/api/agent/docker/snapshot").header("X-DevPilot-Agent-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
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
}

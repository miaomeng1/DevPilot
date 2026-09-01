package com.devpilot.server;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devpilot.server.security.SecretHashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class NginxIntegrationTests {

    private static final String ORIGINAL = "server {\n  listen 80;\n  server_name api.example.com;\n}\n";
    private static final String UPDATED = "server {\n  listen 8080;\n  server_name api.example.com;\n}\n";
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void validationFailureSuccessHistoryAndRollbackWorkEndToEnd() throws Exception {
        String accessToken = setupAdministrator();
        MvcResult serverResult = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"edge-node\"}"))
                .andExpect(status().isOk()).andReturn();
        String agentToken = data(serverResult).path("agentToken").asText();
        register(agentToken);
        uploadSnapshot(agentToken, ORIGINAL, SecretHashing.sha256(ORIGINAL));

        MvcResult configs = mockMvc.perform(get("/api/nginx/configs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].filename", is("api.conf"))).andReturn();
        String configId = data(configs).get(0).path("id").asText();
        mockMvc.perform(get("/api/nginx/hosts").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].available", is(true)))
                .andExpect(jsonPath("$.data[0].configCount", is(1)));

        String invalid = "server { INVALID; }\n";
        MvcResult failedQueued = queueUpdate(accessToken, configId, invalid);
        String failedCommandId = data(failedQueued).path("id").asText();
        claim(agentToken, failedCommandId, "UPDATE", invalid);
        complete(agentToken, failedCommandId, "FAILED", "nginx: configuration test failed", "exit status 1");
        Integer failedExecutionAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE_NGINX_RESULT' AND result = 'FAILED'",
                Integer.class);
        String failedExecutionParams = jdbcTemplate.queryForObject(
                "SELECT request_params FROM audit_log WHERE action = 'UPDATE_NGINX_RESULT' LIMIT 1", String.class);
        if (failedExecutionAudits == null || failedExecutionAudits != 1 || failedExecutionParams == null
                || failedExecutionParams.contains(invalid) || !failedExecutionParams.contains("[CONTENT ")) {
            throw new AssertionError("Expected failed Nginx execution audit with redacted content");
        }
        mockMvc.perform(get("/api/nginx/configs/{id}", configId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content", is(ORIGINAL)));

        MvcResult successQueued = queueUpdate(accessToken, configId, UPDATED);
        String successCommandId = data(successQueued).path("id").asText();
        claim(agentToken, successCommandId, "UPDATE", UPDATED);
        complete(agentToken, successCommandId, "SUCCEEDED", "syntax is ok; test is successful", null);
        mockMvc.perform(get("/api/nginx/configs/{id}", configId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content", is(UPDATED)));

        MvcResult historyResult = mockMvc.perform(get("/api/nginx/configs/{id}/history", configId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.data[1].status", is("FAILED"))).andReturn();
        String historyId = data(historyResult).get(0).path("id").asText();

        MvcResult rollbackQueued = mockMvc.perform(post("/api/nginx/configs/{id}/history/{historyId}/rollback",
                        configId, historyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.action", is("ROLLBACK"))).andReturn();
        String rollbackCommandId = data(rollbackQueued).path("id").asText();
        claim(agentToken, rollbackCommandId, "ROLLBACK", ORIGINAL);
        complete(agentToken, rollbackCommandId, "SUCCEEDED", "syntax is ok; test is successful", null);
        mockMvc.perform(get("/api/nginx/configs/{id}", configId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content", is(ORIGINAL)));
    }

    private MvcResult queueUpdate(String accessToken, String configId, String content) throws Exception {
        return mockMvc.perform(put("/api/nginx/configs/{id}", configId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status", is("REQUESTED"))).andReturn();
    }

    private void claim(String agentToken, String commandId, String action, String content) throws Exception {
        mockMvc.perform(get("/api/agent/nginx/commands/next")
                        .header("X-DevPilot-Agent-Token", agentToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.commandId", is(commandId)))
                .andExpect(jsonPath("$.data.action", is(action)))
                .andExpect(jsonPath("$.data.content", is(content)));
    }

    private void complete(String agentToken, String commandId, String statusValue,
                          String validationOutput, String errorMessage) throws Exception {
        mockMvc.perform(post("/api/agent/nginx/commands/{id}/result", commandId)
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", statusValue,
                                "validationOutput", validationOutput,
                                "errorMessage", errorMessage == null ? "" : errorMessage))))
                .andExpect(status().isOk());
    }

    private void uploadSnapshot(String token, String content, String hash) throws Exception {
        Map<String, Object> file = Map.of("filename", "api.conf", "content", content, "contentHash", hash);
        Map<String, Object> payload = Map.of(
                "agentVersion", "0.1.0", "enabled", true, "available", true,
                "nginxVersion", "nginx/1.29.1", "configPath", "/etc/nginx/conf.d",
                "collectedAt", Instant.now().toString(), "files", List.of(file));
        mockMvc.perform(post("/api/agent/nginx/snapshot")
                        .header("X-DevPilot-Agent-Token", token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private void register(String token) throws Exception {
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"%s","hostname":"edge-host","ip":"10.0.0.40","os":"Linux",
                         "kernel":"6.8","arch":"amd64","agentVersion":"0.1.0","cpuModel":"CPU",
                         "cpuCores":4,"memoryTotal":8000000000,"diskTotal":100000000000}
                        """.formatted(token))).andExpect(status().isOk());
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

package com.devpilot.server;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class AdministrationIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

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
    void settingsUsersRbacAndSanitizedAuditWorkEndToEnd() throws Exception {
        String adminToken = setupAdministrator();
        mockMvc.perform(put("/api/settings").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "systemName", "Engineering Control", "logoUrl", "", "defaultTheme", "LIGHT",
                                "accessTokenTtlMinutes", 15, "refreshTokenTtlHours", 24,
                                "agentHeartbeatTimeoutSeconds", 45, "metricIntervalSeconds", 20,
                                "logDefaultLines", "500", "webhookEnabled", false, "webhookUrl", ""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.systemName", is("Engineering Control")))
                .andExpect(jsonPath("$.data.metricIntervalSeconds", is(20)));
        mockMvc.perform(get("/api/system/public-settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultTheme", is("LIGHT")))
                .andExpect(jsonPath("$.data.logDefaultLines", is(500)));

        MvcResult relogin = login("admin", "DevPilot-Admin-2026", status().isOk());
        adminToken = data(relogin).path("accessToken").asText();
        mockMvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":"developer","displayName":"Developer User","email":"dev@example.com",
                                 "role":"DEVELOPER","password":"Developer-2026","confirmPassword":"Developer-2026"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role", is("DEVELOPER")));
        MvcResult users = mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(2))).andReturn();
        String adminId = data(users).get(0).path("username").asText().equals("admin")
                ? data(users).get(0).path("id").asText() : data(users).get(1).path("id").asText();
        String developerId = data(users).get(0).path("username").asText().equals("developer")
                ? data(users).get(0).path("id").asText() : data(users).get(1).path("id").asText();

        mockMvc.perform(put("/api/users/{id}", adminId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName":"Administrator","email":"","role":"VIEWER","status":"ACTIVE"}
                                """))
                .andExpect(status().isBadRequest());
        login("admin", "Wrong-Password-2026", status().isUnauthorized());
        mockMvc.perform(put("/api/nginx/configs/999").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"server { secret_directive; }\"}"))
                .andExpect(status().isNotFound());

        String developerToken = data(login("developer", "Developer-2026", status().isOk())).path("accessToken").asText();
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + developerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/users/{id}", developerId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName":"Developer User","email":"dev@example.com",
                                 "role":"VIEWER","status":"ACTIVE"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role", is("VIEWER")));
        mockMvc.perform(get("/api/servers").header(HttpHeaders.AUTHORIZATION, "Bearer " + developerToken))
                .andExpect(status().isUnauthorized());
        String viewerToken = data(login("developer", "Developer-2026", status().isOk())).path("accessToken").asText();
        jdbcTemplate.update("UPDATE sys_user SET failed_login_count = 5, locked_until = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15), Long.valueOf(developerId));
        mockMvc.perform(put("/api/users/{id}/password", developerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"password":"Developer-Changed-2026","confirmPassword":"Developer-Changed-2026"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/servers").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isUnauthorized());
        login("developer", "Developer-2026", status().isUnauthorized());
        developerToken = data(login("developer", "Developer-Changed-2026", status().isOk()))
                .path("accessToken").asText();
        MvcResult serverResult = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"retired-node\"}"))
                .andExpect(status().isOk()).andReturn();
        String serverId = data(serverResult).path("server").path("id").asText();
        mockMvc.perform(delete("/api/servers/{id}", serverId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + developerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/servers/{id}", serverId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        Integer deletedServer = jdbcTemplate.queryForObject(
                "SELECT deleted FROM server_node WHERE id = ?", Integer.class, Long.valueOf(serverId));
        Integer activeAgentTokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_token WHERE server_id = ? AND revoked_at IS NULL",
                Integer.class, Long.valueOf(serverId));
        if (deletedServer == null || deletedServer != 1 || activeAgentTokens == null || activeAgentTokens != 0) {
            throw new AssertionError("Expected server soft deletion to revoke every Agent token");
        }
        mockMvc.perform(put("/api/users/{id}", developerId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName":"Developer User","email":"dev@example.com",
                                 "role":"VIEWER","status":"DISABLED"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("DISABLED")));
        mockMvc.perform(get("/api/servers").header(HttpHeaders.AUTHORIZATION, "Bearer " + developerToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(7)));
        Integer failedUpdates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE_USER' AND result = 'FAILED'", Integer.class);
        Integer failedLogins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND result = 'FAILED'", Integer.class);
        String failedLoginParams = jdbcTemplate.queryForObject(
                "SELECT request_params FROM audit_log WHERE action = 'LOGIN' AND result = 'FAILED' ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        String nginxParams = jdbcTemplate.queryForObject(
                "SELECT request_params FROM audit_log WHERE action = 'UPDATE_NGINX' ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        String settingsParams = jdbcTemplate.queryForObject(
                "SELECT request_params FROM audit_log WHERE action = 'UPDATE_SETTINGS' ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        if (failedUpdates == null || failedUpdates != 1 || failedLogins == null || failedLogins < 1
                || failedLoginParams == null || !failedLoginParams.contains("[REDACTED]")
                || failedLoginParams.contains("Wrong-Password-2026") || nginxParams == null
                || !nginxParams.contains("CONTENT") || nginxParams.contains("secret_directive")
                || settingsParams == null || !settingsParams.contains("\"accessTokenTtlMinutes\":15")) {
            throw new AssertionError("Expected failed operations and structurally redacted audit parameters");
        }

        mockMvc.perform(delete("/api/users/{id}", developerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":"developer","displayName":"Replacement User","email":"replacement@example.com",
                                 "role":"VIEWER","password":"Replacement-2026","confirmPassword":"Replacement-2026"}
                                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is(40940)));
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

    private MvcResult login(String username, String password,
                            org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(expected).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}

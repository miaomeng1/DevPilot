package com.devpilot.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devpilot.server.agent.service.AgentRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class ServerAgentIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentRegistrationService agentRegistrationService;

    @BeforeEach
    void resetDatabase() {
        TestDatabaseReset.reset(jdbcTemplate);
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
    void agentTokenRegistrationHeartbeatAndOfflineTransitionWorkEndToEnd() throws Exception {
        String accessToken = setupAdministratorAndReadAccessToken();

        MvcResult created = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"prod-server-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.server.status", is("UNKNOWN")))
                .andExpect(jsonPath("$.data.agentToken", startsWith("dp_agent_")))
                .andExpect(jsonPath("$.data.installCommand", containsString("install-agent.sh")))
                .andReturn();

        JsonNode createData = responseData(created);
        String serverId = createData.path("server").path("id").asText();
        String agentToken = createData.path("agentToken").asText();
        String storedHash = jdbcTemplate.queryForObject("SELECT token_hash FROM agent_token", String.class);
        if (agentToken.equals(storedHash) || storedHash == null || storedHash.length() != 64) {
            throw new AssertionError("Agent token must only be stored as a SHA-256 hash");
        }

        mockMvc.perform(post("/api/agent/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token":"%s",
                                  "hostname":"prod-01",
                                  "ip":"10.0.0.11",
                                  "os":"Ubuntu 24.04",
                                  "kernel":"6.8.0",
                                  "arch":"amd64",
                                  "agentVersion":"1.0.0",
                                  "cpuModel":"AMD EPYC",
                                  "cpuCores":8,
                                  "memoryTotal":17179869184,
                                  "diskTotal":536870912000
                                }
                                """.formatted(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverId", is(serverId)))
                .andExpect(jsonPath("$.data.heartbeatIntervalSeconds", is(10)));

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentVersion\":\"1.0.1\",\"listeningTcpPorts\":[8080,80,8080]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ONLINE")));

        org.junit.jupiter.api.Assertions.assertEquals("80,8080", jdbcTemplate.queryForObject(
                "SELECT listening_tcp_ports FROM server_node WHERE id = ?", String.class, Long.valueOf(serverId)));
        org.junit.jupiter.api.Assertions.assertNotNull(jdbcTemplate.queryForObject(
                "SELECT ports_collected_at FROM server_node WHERE id = ?", LocalDateTime.class, Long.valueOf(serverId)));

        mockMvc.perform(get("/api/servers/{id}", serverId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hostname", is("prod-01")))
                .andExpect(jsonPath("$.data.agentVersion", is("1.0.1")))
                .andExpect(jsonPath("$.data.status", is("ONLINE")));

        jdbcTemplate.update("UPDATE server_node SET last_heartbeat = ?, agent_status = 'ONLINE' WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(31), Long.valueOf(serverId));
        int changed = agentRegistrationService.markOfflineNow();
        if (changed != 1) {
            throw new AssertionError("Expected one server to transition offline, got " + changed);
        }
        mockMvc.perform(get("/api/servers/{id}", serverId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("OFFLINE")));

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("X-DevPilot-Agent-Token", "dp_agent_invalid_token_value_123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(40101)));
    }

    private String setupAdministratorAndReadAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"admin",
                                  "password":"DevPilot-Admin-2026",
                                  "confirmPassword":"DevPilot-Admin-2026",
                                  "displayName":"Administrator"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return responseData(result).path("accessToken").asText();
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}

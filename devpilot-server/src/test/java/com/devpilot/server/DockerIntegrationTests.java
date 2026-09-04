package com.devpilot.server;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
class DockerIntegrationTests {

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
    void snapshotsCommandsAndSecretMaskingWorkEndToEnd() throws Exception {
        String accessToken = setupAdministrator();
        MvcResult created = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"docker-node\"}"))
                .andExpect(status().isOk()).andReturn();
        String agentToken = data(created).path("agentToken").asText();
        register(agentToken);

        mockMvc.perform(post("/api/agent/docker/snapshot")
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(snapshotPayload()))
                .andExpect(status().isOk());

        MvcResult containersResult = mockMvc.perform(get("/api/docker/containers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name", is("api")))
                .andExpect(jsonPath("$.data[0].composeProject", is("personal-cloud")))
                .andExpect(jsonPath("$.data[0].composeService", is("api")))
                .andExpect(jsonPath("$.data[0].environment", hasItem("DB_PASSWORD=******")))
                .andExpect(jsonPath("$.data[0].environment", hasItem("MODE=production")))
                .andReturn();
        String runningId = data(containersResult).get(0).path("id").asText();
        String stoppedId = data(containersResult).get(1).path("id").asText();

        mockMvc.perform(get("/api/docker/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.containers", is(2)))
                .andExpect(jsonPath("$.data.running", is(1)));
        mockMvc.perform(get("/api/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.containerTotal", is(2)))
                .andExpect(jsonPath("$.data.summary.containerRunning", is(1)));

        MvcResult queued = mockMvc.perform(post("/api/docker/containers/{id}/restart", runningId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status", is("REQUESTED"))).andReturn();
        String commandId = data(queued).path("id").asText();
        mockMvc.perform(get("/api/agent/docker/commands/next")
                        .header("X-DevPilot-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId", is(commandId)))
                .andExpect(jsonPath("$.data.action", is("RESTART")));
        mockMvc.perform(post("/api/agent/docker/commands/{id}/result", commandId)
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/docker/commands/{id}", commandId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SUCCEEDED")));
        Integer executionAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'RESTART_CONTAINER_RESULT' AND result = 'SUCCESS'",
                Integer.class);
        if (executionAudits == null || executionAudits != 1) {
            throw new AssertionError("Expected successful Docker execution result audit");
        }

        mockMvc.perform(post("/api/docker/containers/{id}/remove", stoppedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmName\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/docker/containers/{id}/remove", stoppedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmName\":\"worker\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.action", is("REMOVE")));
    }

    private String snapshotPayload() {
        return """
                {"agentVersion":"0.1.0","available":true,"engineVersion":"28.3.3","images":4,
                "volumes":2,"networks":3,"collectedAt":"%s","containers":[
                  {"containerId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "name":"/api","image":"example/api:1.2.0","state":"running","status":"Up 2 hours",
                   "health":"healthy","cpuUsage":12.4,"memoryUsage":268435456,"memoryLimit":1073741824,
                   "networkRx":1000,"networkTx":2000,"ipAddress":"172.18.0.2",
                   "ports":["0.0.0.0:8080→8080/tcp"],"createdAt":"2026-08-30T00:00:00Z",
                   "startedAt":"2026-08-31T00:00:00Z","restartCount":1,"networkMode":"bridge",
                   "composeProject":"personal-cloud","composeService":"api",
                   "volumes":["/data/api:/app/data:rw"],
                   "environment":["MODE=production","DB_PASSWORD=plain-secret"]},
                  {"containerId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "name":"worker","image":"example/worker:1.0","state":"exited","status":"Exited (0)",
                   "cpuUsage":0,"memoryUsage":0,"memoryLimit":0,"networkRx":0,"networkTx":0,
                   "ports":[],"createdAt":"2026-08-29T00:00:00Z","restartCount":0,
                   "volumes":[],"environment":[]}
                ]}
                """.formatted(Instant.now());
    }

    private void register(String token) throws Exception {
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"%s","hostname":"docker-host","ip":"10.0.0.20","os":"Linux",
                        "kernel":"6.8","arch":"amd64","agentVersion":"0.1.0","cpuModel":"CPU",
                        "cpuCores":8,"memoryTotal":16000000000,"diskTotal":500000000000}
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

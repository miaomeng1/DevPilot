package com.devpilot.server;

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
class ApplicationIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void applicationBindingHealthAndReleaseHistoryWorkEndToEnd() throws Exception {
        String accessToken = setupAdministrator();
        MvcResult createdServer = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"production\"}"))
                .andExpect(status().isOk()).andReturn();
        String agentToken = data(createdServer).path("agentToken").asText();
        register(agentToken);
        mockMvc.perform(post("/api/agent/docker/snapshot")
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON).content(snapshotPayload()))
                .andExpect(status().isOk());

        MvcResult containers = mockMvc.perform(get("/api/docker/containers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        String containerId = data(containers).get(0).path("id").asText();
        String serverId = data(containers).get(0).path("serverId").asText();

        MvcResult createdApplication = mockMvc.perform(post("/api/applications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"EasyBBS","code":"easybbs","description":"Community API",
                                 "environment":"PRODUCTION","serverId":"%s","containerSnapshotId":"%s",
                                 "currentVersion":"v1.2.3","accessUrl":"https://bbs.example.com",
                                 "healthCheckUrl":"http://127.0.0.1:9090/actuator/health"}
                                """.formatted(serverId, containerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("RUNNING")))
                .andExpect(jsonPath("$.data.healthStatus", is("UNKNOWN")))
                .andExpect(jsonPath("$.data.containerName", is("easybbs-api")))
                .andReturn();
        String applicationId = data(createdApplication).path("id").asText();

        mockMvc.perform(get("/api/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.applicationTotal", is(1)))
                .andExpect(jsonPath("$.data.serviceStatuses[0].name", is("EasyBBS")));

        mockMvc.perform(get("/api/agent/applications/health/next")
                        .header("X-DevPilot-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationId", is(applicationId)))
                .andExpect(jsonPath("$.data.healthCheckUrl", is("http://127.0.0.1:9090/actuator/health")));
        mockMvc.perform(get("/api/agent/applications/health/next")
                        .header("X-DevPilot-Agent-Token", agentToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(post("/api/agent/applications/health/{id}/result", applicationId)
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HEALTHY\",\"latencyMillis\":42,\"httpStatus\":200,\"message\":\"reported status UP\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/applications/{id}", applicationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.healthStatus", is("HEALTHY")))
                .andExpect(jsonPath("$.data.healthMessage", is("HTTP 200 · 42 ms · reported status UP")));

        mockMvc.perform(post("/api/applications/{id}/deployments", applicationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"version":"v1.2.4","dockerImage":"easybbs/api:v1.2.4",
                                 "result":"SUCCESS","logs":"Published by GitHub Actions"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result", is("SUCCESS")));
        mockMvc.perform(get("/api/applications/{id}/deployments", applicationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].version", is("v1.2.4")));
        mockMvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.todayDeployments", is(1)))
                .andExpect(jsonPath("$.data.recentDeployments[0].applicationName", is("EasyBBS")));

        mockMvc.perform(post("/api/applications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Unsafe","code":"unsafe-app","environment":"DEV",
                                 "serverId":"%s","containerSnapshotId":"%s","healthCheckUrl":"file:///etc/passwd"}
                                """.formatted(serverId, containerId)))
                .andExpect(status().isBadRequest());
    }

    private String snapshotPayload() {
        return """
                {"agentVersion":"0.1.0","available":true,"engineVersion":"28.3.3","images":1,
                 "volumes":0,"networks":1,"collectedAt":"%s","containers":[
                  {"containerId":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                   "name":"easybbs-api","image":"easybbs/api:v1.2.3","state":"running","status":"Up 1 hour",
                   "health":"healthy","cpuUsage":3.2,"memoryUsage":134217728,"memoryLimit":536870912,
                   "networkRx":10,"networkTx":20,"ports":["0.0.0.0:9090→9090/tcp"],
                   "createdAt":"2026-08-30T00:00:00Z","startedAt":"2026-08-31T00:00:00Z",
                   "restartCount":0,"volumes":[],"environment":[]}]}
                """.formatted(Instant.now());
    }

    private void register(String token) throws Exception {
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"%s","hostname":"prod-host","ip":"10.0.0.30","os":"Linux",
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

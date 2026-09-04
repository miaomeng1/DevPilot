package com.devpilot.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
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
class CapacityPlanningIntegrationTests {

    private static final long GIBIBYTE = 1024L * 1024 * 1024;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM alert_notification");
        jdbcTemplate.update("DELETE FROM alert_maintenance_window");
        jdbcTemplate.update("DELETE FROM alert_notification_route");
        jdbcTemplate.update("DELETE FROM alert_condition_state");
        jdbcTemplate.update("DELETE FROM alert_event");
        jdbcTemplate.update("DELETE FROM alert_rule");
        jdbcTemplate.update("DELETE FROM service_installation");
        jdbcTemplate.update("DELETE FROM application_environment_variable");
        jdbcTemplate.update("DELETE FROM application_environment_state");
        jdbcTemplate.update("DELETE FROM cicd_preview");
        jdbcTemplate.update("DELETE FROM cicd_deployment");
        jdbcTemplate.update("DELETE FROM cicd_pipeline_run");
        jdbcTemplate.update("DELETE FROM cicd_configuration");
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
    void ranksFreshEligibleNodeAndExplainsHardCapacityBlocks() throws Exception {
        String accessToken = setupAdministrator();
        MvcResult healthy = createServer(accessToken, "healthy-node");
        String healthyId = data(healthy).path("server").path("id").asText();
        String healthyToken = data(healthy).path("agentToken").asText();
        register(healthyToken, "healthy-host");
        uploadMetric(healthyToken, 18.0, 0.4, 2 * GIBIBYTE, 6 * GIBIBYTE,
                20 * GIBIBYTE, 80 * GIBIBYTE);
        uploadDocker(healthyToken);

        MvcResult pending = createServer(accessToken, "pending-node");
        String pendingId = data(pending).path("server").path("id").asText();

        mockMvc.perform(get("/api/capacity/plan")
                        .param("memoryBytes", Long.toString(GIBIBYTE))
                        .param("diskBytes", Long.toString(5 * GIBIBYTE))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict", is("SAFE")))
                .andExpect(jsonPath("$.data.recommendedServerId", is(healthyId)))
                .andExpect(jsonPath("$.data.servers", hasSize(2)))
                .andExpect(jsonPath("$.data.servers[0].serverId", is(healthyId)))
                .andExpect(jsonPath("$.data.servers[0].eligible", is(true)))
                .andExpect(jsonPath("$.data.servers[0].recommended", is(true)))
                .andExpect(jsonPath("$.data.servers[0].score", greaterThan(70)))
                .andExpect(jsonPath("$.data.servers[0].memoryAvailableAfter", is(Long.toString(5 * GIBIBYTE))))
                .andExpect(jsonPath("$.data.servers[1].serverId", is(pendingId)))
                .andExpect(jsonPath("$.data.servers[1].eligible", is(false)))
                .andExpect(jsonPath("$.data.servers[1].blockers[0]", containsString("Agent")));

        mockMvc.perform(get("/api/capacity/plan")
                        .param("memoryBytes", Long.toString(64 * GIBIBYTE))
                        .param("diskBytes", Long.toString(5 * GIBIBYTE))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict", is("BLOCKED")))
                .andExpect(jsonPath("$.data.recommendedServerId").isEmpty());

        mockMvc.perform(get("/api/capacity/plan")
                        .param("memoryBytes", "1").param("diskBytes", Long.toString(GIBIBYTE))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    private MvcResult createServer(String accessToken, String name) throws Exception {
        return mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private void register(String token, String hostname) throws Exception {
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"token":"%s","hostname":"%s","ip":"10.0.0.50","os":"Linux",
                 "kernel":"6.8","arch":"amd64","agentVersion":"0.1.0","cpuModel":"CPU",
                 "cpuCores":4,"memoryTotal":%d,"diskTotal":%d}
                """.formatted(token, hostname, 8 * GIBIBYTE, 100 * GIBIBYTE))).andExpect(status().isOk());
    }

    private void uploadMetric(String token, double cpu, double loadOne, long memoryUsed, long memoryAvailable,
                              long diskUsed, long diskFree) throws Exception {
        mockMvc.perform(post("/api/agent/metrics").header("X-DevPilot-Agent-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                {"agentVersion":"0.1.0","collectedAt":"%s","cpuUsage":%s,
                 "loadOne":%s,"loadFive":0.3,"loadFifteen":0.2,
                 "memoryTotal":%d,"memoryUsed":%d,"memoryAvailable":%d,
                 "diskTotal":%d,"diskUsed":%d,"diskFree":%d,
                 "networkBytesSent":100,"networkBytesReceived":200,
                 "networkUploadRate":1.0,"networkDownloadRate":2.0}
                """.formatted(Instant.now(), cpu, loadOne, 8 * GIBIBYTE, memoryUsed, memoryAvailable,
                                100 * GIBIBYTE, diskUsed, diskFree)))
                .andExpect(status().isOk());
    }

    private void uploadDocker(String token) throws Exception {
        mockMvc.perform(post("/api/agent/docker/snapshot").header("X-DevPilot-Agent-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                {"agentVersion":"0.1.0","available":true,"engineVersion":"28.3.3","images":2,
                 "volumes":1,"networks":1,"collectedAt":"%s","containers":[]}
                """.formatted(Instant.now())))
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

package com.devpilot.server;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
class MetricIntegrationTests {

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
    void metricsAreAuthenticatedAggregatedAndExposedToDashboard() throws Exception {
        String accessToken = setupAdministrator();
        MvcResult createResult = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"metrics-node\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode createData = data(createResult);
        String serverId = createData.path("server").path("id").asText();
        String agentToken = createData.path("agentToken").asText();

        mockMvc.perform(post("/api/agent/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","hostname":"metric-host","ip":"10.0.0.12",
                                "os":"Linux","kernel":"6.8","arch":"amd64","agentVersion":"1.0.0",
                                "cpuModel":"Test CPU","cpuCores":4,"memoryTotal":1000,"diskTotal":2000}
                                """.formatted(agentToken)))
                .andExpect(status().isOk());

        upload(agentToken, 10.0, 400);
        upload(agentToken, 20.0, 600);

        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM server_metric", Integer.class);
        Integer samples = jdbcTemplate.queryForObject("SELECT sample_count FROM server_metric", Integer.class);
        Double cpu = jdbcTemplate.queryForObject("SELECT cpu_usage FROM server_metric", Double.class);
        if (rows == null || rows != 1 || samples == null || samples != 2 || cpu == null || cpu != 15.0) {
            throw new AssertionError("Expected one two-sample minute aggregate with 15% CPU");
        }

        mockMvc.perform(get("/api/servers/{id}/metrics", serverId)
                        .param("range", "1h")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionSeconds", is(60)))
                .andExpect(jsonPath("$.data.points", hasSize(1)))
                .andExpect(jsonPath("$.data.current.cpuUsage", is(15.0)))
                .andExpect(jsonPath("$.data.current.memoryUsed", is("500")));

        mockMvc.perform(get("/api/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.serverTotal", is(1)))
                .andExpect(jsonPath("$.data.summary.serverOnline", is(1)))
                .andExpect(jsonPath("$.data.serverResources[0].current.cpuUsage", is(15.0)))
                .andExpect(jsonPath("$.data.trend.length()", greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/monitor").param("range", "1h")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reportingServers", is(1)))
                .andExpect(jsonPath("$.data.summary.averageCpuUsage", is(15.0)))
                .andExpect(jsonPath("$.data.summary.averageMemoryUsage", is(50.0)))
                .andExpect(jsonPath("$.data.summary.averageDiskUsage", is(25.0)))
                .andExpect(jsonPath("$.data.servers[0].name", is("metrics-node")));

        mockMvc.perform(post("/api/agent/metrics")
                        .header("X-DevPilot-Agent-Token", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricPayload(10.0, 100)))
                .andExpect(status().isUnauthorized());
    }

    private void upload(String token, double cpu, long memoryUsed) throws Exception {
        mockMvc.perform(post("/api/agent/metrics")
                        .header("X-DevPilot-Agent-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricPayload(cpu, memoryUsed)))
                .andExpect(status().isOk());
    }

    private String metricPayload(double cpu, long memoryUsed) {
        return """
                {"agentVersion":"1.0.1","collectedAt":"%s","cpuUsage":%s,
                "loadOne":0.5,"loadFive":0.4,"loadFifteen":0.3,
                "memoryTotal":1000,"memoryUsed":%d,"memoryAvailable":%d,
                "diskTotal":2000,"diskUsed":500,"diskFree":1500,
                "networkBytesSent":10000,"networkBytesReceived":20000,
                "networkUploadRate":120.5,"networkDownloadRate":240.5}
                """.formatted(Instant.now(), cpu, memoryUsed, 1000 - memoryUsed);
    }

    private String setupAdministrator() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
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

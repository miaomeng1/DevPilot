package com.devpilot.server;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class ServiceTemplateIntegrationTests {

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
        jdbcTemplate.update("DELETE FROM service_installation");
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
    void installsPinnedLoopbackTemplateAndAutomaticallyRegistersApplication() throws Exception {
        String accessToken = setupAdministrator();
        MvcResult serverResult = mockMvc.perform(post("/api/servers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"template-node\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode server = data(serverResult);
        String serverId = server.path("server").path("id").asText();
        String agentToken = server.path("agentToken").asText();
        register(agentToken);
        uploadSnapshot(agentToken, "[]");

        MvcResult catalogResult = mockMvc.perform(get("/api/service-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andReturn();
        for (JsonNode template : data(catalogResult)) {
            String image = template.path("image").asText();
            if (!image.contains(":") || image.endsWith(":latest")) {
                throw new AssertionError("Service catalog images must use a pinned version: " + image);
            }
        }

        String request = """
                {"serverId":"%s","displayName":"我的可用性监控","instanceName":"personal-uptime",
                 "environment":"PRODUCTION","hostPort":3001,"timezone":"Asia/Shanghai"}
                """.formatted(serverId);
        MvcResult installationResult = mockMvc.perform(post("/api/service-templates/uptime-kuma/installations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status", is("REQUESTED")))
                .andExpect(jsonPath("$.data.image", is("louislam/uptime-kuma:2.5.0")))
                .andReturn();
        String installationId = data(installationResult).path("id").asText();

        mockMvc.perform(post("/api/service-templates/uptime-kuma/installations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/servers/{id}", serverId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/agent/service-templates/installations/next")
                        .header("X-DevPilot-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installationId", is(installationId)))
                .andExpect(jsonPath("$.data.templateId", is("uptime-kuma")))
                .andExpect(jsonPath("$.data.instanceName", is("personal-uptime")))
                .andExpect(jsonPath("$.data.hostPort", is(3001)))
                .andExpect(jsonPath("$.data.timezone", is("Asia/Shanghai")));

        mockMvc.perform(post("/api/agent/service-templates/installations/{id}/result", installationId)
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\",\"containerId\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        String containerId = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        mockMvc.perform(post("/api/agent/service-templates/installations/{id}/result", installationId)
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\",\"containerId\":\"" + containerId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/service-templates/installations/{id}/result", installationId)
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\",\"containerId\":\"" + containerId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/service-templates/installations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status", is("DISCOVERING")));

        uploadSnapshot(agentToken, """
                [{"containerId":"%s","name":"devpilot-personal-uptime",
                  "image":"louislam/uptime-kuma:2.5.0","state":"running","status":"Up 5 seconds",
                  "cpuUsage":1.2,"memoryUsage":104857600,"memoryLimit":805306368,
                  "networkRx":100,"networkTx":200,"ipAddress":"172.18.0.8",
                  "ports":["127.0.0.1:3001→3001/tcp"],"createdAt":"2026-09-04T00:00:00Z",
                  "startedAt":"2026-09-04T00:00:01Z","restartCount":0,"networkMode":"bridge",
                  "volumes":["devpilot-personal-uptime-data:/app/data:rw"],
                  "environment":["TZ=Asia/Shanghai"]}]
                """.formatted(containerId));

        MvcResult readyResult = mockMvc.perform(get("/api/service-templates/installations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status", is("READY")))
                .andExpect(jsonPath("$.data[0].hostPort", is(3001)))
                .andReturn();
        String applicationId = data(readyResult).get(0).path("applicationId").asText();
        mockMvc.perform(get("/api/applications/{id}", applicationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployType", is("TEMPLATE")))
                .andExpect(jsonPath("$.data.code", is("personal-uptime")))
                .andExpect(jsonPath("$.data.currentVersion", is("2.5.0")))
                .andExpect(jsonPath("$.data.healthCheckUrl", is("http://127.0.0.1:3001")))
                .andExpect(jsonPath("$.data.accessUrl").doesNotExist());

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'INSTALL_SERVICE_TEMPLATE_RESULT' AND result = 'SUCCESS'",
                Integer.class);
        if (audits == null || audits != 1) {
            throw new AssertionError("Expected one successful service template execution audit");
        }
    }

    private void uploadSnapshot(String agentToken, String containers) throws Exception {
        mockMvc.perform(post("/api/agent/docker/snapshot")
                        .header("X-DevPilot-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentVersion":"0.1.0","available":true,"engineVersion":"28.3.3",
                                 "images":4,"volumes":2,"networks":3,"collectedAt":"%s","containers":%s}
                                """.formatted(Instant.now(), containers)))
                .andExpect(status().isOk());
    }

    private void register(String token) throws Exception {
        mockMvc.perform(post("/api/agent/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"%s","hostname":"template-host","ip":"10.0.0.30","os":"Linux",
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

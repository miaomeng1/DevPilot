package com.devpilot.server;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceIntegrationTests {
    private static final String REPORT_SECRET = "test-maintenance-report-secret-at-least-thirty-two-bytes";
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM maintenance_restore_drill");
        jdbcTemplate.update("DELETE FROM maintenance_backup_report");
        jdbcTemplate.update("DELETE FROM audit_log");
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
    void verifiedReportIsIdempotentAndVisibleToAdministrator() throws Exception {
        String accessToken = setupAdministrator();
        mockMvc.perform(get("/api/maintenance/backups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("NO_BACKUP")))
                .andExpect(jsonPath("$.data.reportingConfigured", is(true)));

        String body = reportBody(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        mockMvc.perform(post("/api/maintenance/backups/report").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", "sha256=" + "0".repeat(64)).content(body))
                .andExpect(status().isUnauthorized());

        String signature = sign(body);
        String firstId = null;
        for (int request = 0; request < 2; request++) {
            MvcResult result = mockMvc.perform(post("/api/maintenance/backups/report")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-DevPilot-Signature", signature).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.fileName", is("devpilot-20260904T010000Z.tar.gz")))
                    .andExpect(jsonPath("$.data.sizeBytes", is("4096")))
                    .andReturn();
            String id = data(result).path("id").asText();
            if (firstId == null) firstId = id; else org.junit.jupiter.api.Assertions.assertEquals(firstId, id);
        }
        org.junit.jupiter.api.Assertions.assertEquals(1L,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM maintenance_backup_report", Long.class));

        mockMvc.perform(get("/api/maintenance/backups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state", is("HEALTHY")))
                .andExpect(jsonPath("$.data.latest.sha256", is("a".repeat(64))))
                .andExpect(jsonPath("$.data.reports[0].destinationType", is("LOCAL")));

        mockMvc.perform(post("/api/maintenance/restore-drills")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"backupReportId":"%s","environment":"ISOLATED","result":"PASSED",
                                 "notes":"Login, Agent reconnect, encrypted provider and rollback verified"}
                                """.formatted(firstId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backupFileName", is("devpilot-20260904T010000Z.tar.gz")))
                .andExpect(jsonPath("$.data.performedByName", is("Administrator")))
                .andExpect(jsonPath("$.data.result", is("PASSED")));

        mockMvc.perform(get("/api/maintenance/backups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestDrill.environment", is("ISOLATED")))
                .andExpect(jsonPath("$.data.latestDrill.result", is("PASSED")));
    }

    @Test
    void futureDatedReportIsRejected() throws Exception {
        String body = reportBody(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        mockMvc.perform(post("/api/maintenance/backups/report").contentType(MediaType.APPLICATION_JSON)
                        .header("X-DevPilot-Signature", sign(body)).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(40061)));
    }

    private String reportBody(LocalDateTime createdAt) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("fileName", "devpilot-20260904T010000Z.tar.gz");
        body.put("sizeBytes", 4096);
        body.put("sha256", "a".repeat(64));
        body.put("destinationType", "LOCAL");
        body.put("createdAt", createdAt.toString());
        return objectMapper.writeValueAsString(body);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(REPORT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
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

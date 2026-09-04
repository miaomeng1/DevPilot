package com.devpilot.server;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devpilot.server.alert.entity.AlertEventEntity;
import com.devpilot.server.automation.service.AutomationWebhookDeliveryService;
import com.devpilot.server.automation.service.AutomationWebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
class AutomationWebhookIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AutomationWebhookService webhookService;
    @Autowired private AutomationWebhookDeliveryService deliveryService;
    private HttpServer receiver;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM automation_webhook_delivery");
        jdbcTemplate.update("DELETE FROM automation_webhook_subscription");
        jdbcTemplate.update("DELETE FROM api_access_token");
        jdbcTemplate.update("DELETE FROM service_installation");
        jdbcTemplate.update("DELETE FROM application_environment_variable");
        jdbcTemplate.update("DELETE FROM application_environment_state");
        jdbcTemplate.update("DELETE FROM cicd_preview");
        jdbcTemplate.update("DELETE FROM cicd_deployment");
        jdbcTemplate.update("DELETE FROM cicd_pipeline_run");
        jdbcTemplate.update("DELETE FROM cicd_configuration");
        jdbcTemplate.update("DELETE FROM alert_notification");
        jdbcTemplate.update("DELETE FROM alert_maintenance_window");
        jdbcTemplate.update("DELETE FROM alert_notification_route");
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
    void stopReceiver() {
        if (receiver != null) receiver.stop(0);
        jdbcTemplate.update("DELETE FROM automation_webhook_delivery");
        jdbcTemplate.update("DELETE FROM automation_webhook_subscription");
    }

    @Test
    void emitsSignedCloudEventAndTracksSuccessfulDelivery() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> deliveryId = new AtomicReference<>();
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signature.set(exchange.getRequestHeaders().getFirst("X-DevPilot-Signature-256"));
            deliveryId.set(exchange.getRequestHeaders().getFirst("X-DevPilot-Delivery"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();

        String admin = setupAdministrator();
        MvcResult created = mockMvc.perform(post("/api/automation/webhooks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Local receiver","endpointUrl":"http://127.0.0.1:%d/hook",
                                 "eventTypes":["ALERT_FIRING","DEPLOYMENT_FAILED"]}
                                """.formatted(receiver.getAddress().getPort())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.oneTimeSecret", startsWith("dpwhsec_")))
                .andReturn();
        String secret = data(created).path("oneTimeSecret").asText();

        mockMvc.perform(post("/api/automation/webhooks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Local receiver","endpointUrl":"http://127.0.0.1:%d/hook",
                                 "eventTypes":["ALERT_FIRING"]}
                                """.formatted(receiver.getAddress().getPort())))
                .andExpect(status().isConflict());

        AlertEventEntity alert = new AlertEventEntity();
        alert.setId(8001L);
        alert.setServerId(9001L);
        alert.setResourceType("SERVER");
        alert.setResourceId("9001");
        alert.setResourceName("edge-1");
        alert.setSeverity("CRITICAL");
        alert.setStatus("FIRING");
        alert.setMessage("Disk usage is critical");
        webhookService.publishAlert(alert, "FIRING");
        deliveryService.deliverPending();

        JsonNode event = objectMapper.readTree(body.get());
        assertEquals("1.0", event.path("specversion").asText());
        assertEquals("dev.devpilot.alert.firing.v1", event.path("type").asText());
        assertEquals(event.path("id").asText(), deliveryId.get());
        assertEquals("sha256=" + hmac(secret, body.get()), signature.get());

        mockMvc.perform(get("/api/automation/webhooks/deliveries")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.data[0].responseCode", is(204)));

        mockMvc.perform(post("/api/automation/webhooks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unsafe\",\"endpointUrl\":\"http://example.com/hook\",\"eventTypes\":[\"ALERT_FIRING\"]}"))
                .andExpect(status().isBadRequest());
        assertTrue(jdbcTemplate.queryForObject("SELECT endpoint_url_encrypted FROM automation_webhook_subscription LIMIT 1", String.class).startsWith("v1:"));
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

    private static String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}

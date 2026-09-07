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
        TestDatabaseReset.reset(jdbcTemplate);
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
                                 "eventTypes":["ALERT_FIRING","DEPLOYMENT_FAILED","BUILD_FAILED","ROLLBACK_HEALTHY","ROLLBACK_FAILED"]}
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
        var application = new com.devpilot.server.application.entity.ApplicationEntity();
        application.setId(7001L);
        application.setServerId(9001L);
        application.setName("Demo");
        application.setEnvironment("PRODUCTION");
        var deployment = new com.devpilot.server.cicd.entity.CicdDeploymentEntity();
        deployment.setId(6001L);
        deployment.setApplicationId(7001L);
        deployment.setDeploymentKind("ROLLBACK");
        deployment.setStatus("HEALTHY");
        webhookService.publishDeployment(deployment, application, true);
        deliveryService.deliverPending();
        event = objectMapper.readTree(body.get());
        assertEquals("dev.devpilot.rollback.healthy.v1", event.path("type").asText());
        assertEquals("PRODUCTION", event.path("data").path("environment").asText());
        assertEquals("sha256=" + hmac(secret, body.get()), signature.get());
        deployment.setId(6002L);
        deployment.setStatus("UNHEALTHY");
        webhookService.publishDeployment(deployment, application, false);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery WHERE event_type='ROLLBACK_FAILED'", Integer.class));
    }

    @Test
    void failedDeliveryCanRetryWithoutChangingEventAndSuccessfulDeliveryCannotReplay() throws Exception {
        var responseCode = new java.util.concurrent.atomic.AtomicInteger(503);
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var identities = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var sending = new java.util.concurrent.CountDownLatch(1);
        var releaseResponse = new java.util.concurrent.CountDownLatch(1);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/retry", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            identities.add(exchange.getRequestHeaders().getFirst("X-DevPilot-Delivery"));
            if (responseCode.get() == 204) {
                sending.countDown();
                try { releaseResponse.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            exchange.sendResponseHeaders(responseCode.get(), -1);
            exchange.close();
        });
        receiver.start();
        String admin = setupAdministrator();
        mockMvc.perform(post("/api/automation/webhooks").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Retry receiver","endpointUrl":"http://127.0.0.1:%d/retry","eventTypes":["ALERT_FIRING"]}
                """.formatted(receiver.getAddress().getPort()))).andExpect(status().isOk());
        var alert = new AlertEventEntity();
        alert.setId(8002L); alert.setServerId(9001L); alert.setResourceType("SERVER");
        alert.setResourceId("9001"); alert.setResourceName("edge-1"); alert.setSeverity("CRITICAL");
        alert.setStatus("FIRING"); alert.setMessage("Offline fixture");
        webhookService.publishAlert(alert, "FIRING");
        deliveryService.deliverPending();
        Long id = jdbcTemplate.queryForObject("SELECT id FROM automation_webhook_delivery", Long.class);
        assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT status FROM automation_webhook_delivery", String.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT attempt_count FROM automation_webhook_delivery", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery WHERE sent_at IS NULL AND next_attempt_at > updated_at", Integer.class));
        deliveryService.deliverPending();
        assertEquals(1, received.size(), "Not-yet-due failures must not be sent immediately again");
        java.util.function.Supplier<Integer> retry = () -> {
            try { return mockMvc.perform(post("/api/automation/webhooks/deliveries/{id}/retry", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn().getResponse().getStatus(); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        };
        var firstRetry = java.util.concurrent.CompletableFuture.supplyAsync(retry);
        var secondRetry = java.util.concurrent.CompletableFuture.supplyAsync(retry);
        assertEquals(java.util.Set.of(200, 409), java.util.Set.of(
                firstRetry.get(10, java.util.concurrent.TimeUnit.SECONDS), secondRetry.get(10, java.util.concurrent.TimeUnit.SECONDS)));
        mockMvc.perform(post("/api/automation/webhooks/deliveries/{id}/retry", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isConflict());
        responseCode.set(204);
        var worker = java.util.concurrent.CompletableFuture.runAsync(deliveryService::deliverPending);
        try {
            assertTrue(sending.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("SENDING", jdbcTemplate.queryForObject("SELECT status FROM automation_webhook_delivery", String.class));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery WHERE claim_token IS NOT NULL AND claim_expires_at IS NOT NULL AND response_code IS NULL AND error_message IS NULL AND sent_at IS NULL", Integer.class));
            deliveryService.deliverPending();
            assertEquals(2, received.size(), "A second worker cannot send a claimed delivery");
            mockMvc.perform(post("/api/automation/webhooks/deliveries/{id}/retry", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isConflict());
        } finally { releaseResponse.countDown(); }
        worker.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, received.size());
        assertEquals(received.getFirst(), received.getLast());
        assertEquals(identities.getFirst(), identities.getLast());
        assertEquals("SUCCEEDED", jdbcTemplate.queryForObject("SELECT status FROM automation_webhook_delivery", String.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery WHERE sent_at IS NOT NULL AND response_code=204 AND error_message IS NULL", Integer.class));
        mockMvc.perform(post("/api/automation/webhooks/deliveries/{id}/retry", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isConflict());
        deliveryService.deliverPending();
        assertEquals(2, received.size());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery", Integer.class));
        // Simulate process death after claiming, without waiting five wall-clock minutes.
        jdbcTemplate.update("UPDATE automation_webhook_delivery SET status='SENDING',claim_token='lost-worker',claim_expires_at=?,attempt_count=4,sent_at=NULL",
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        deliveryService.deliverPending();
        assertEquals(3, received.size());
        assertEquals(identities.getFirst(), identities.getLast());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery WHERE status='SUCCEEDED' AND attempt_count=5 AND claim_token IS NULL", Integer.class));
        jdbcTemplate.update("UPDATE automation_webhook_delivery SET status='SENDING',claim_token='last-lost-worker',claim_expires_at=?,sent_at=NULL",
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        deliveryService.deliverPending();
        assertEquals(3, received.size(), "Expired fifth claim cannot retry automatically without limit");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery WHERE status='FAILED' AND attempt_count=5 AND claim_token IS NULL AND error_message LIKE '%outcome unknown%'", Integer.class));
    }

    @Test
    void lateSuccessCannotOverwriteNewLeaseFailure() throws Exception {
        var oldRequestEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseOldRequest = new java.util.concurrent.CountDownLatch(1);
        var newRequestEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseNewRequest = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.setExecutor(executor);
        receiver.createContext("/late", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int index = calls.incrementAndGet();
            if (index == 1) {
                oldRequestEntered.countDown();
                try { releaseOldRequest.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else {
                newRequestEntered.countDown();
                try { releaseNewRequest.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            exchange.sendResponseHeaders(index == 1 ? 204 : 503, -1);
            exchange.close();
        });
        receiver.start();
        java.util.concurrent.CompletableFuture<Void> oldWorker = null;
        java.util.concurrent.CompletableFuture<Void> newWorker = null;
        try {
            String admin = setupAdministrator();
            mockMvc.perform(post("/api/automation/webhooks").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                    {"name":"Late receiver","endpointUrl":"http://127.0.0.1:%d/late","eventTypes":["ALERT_FIRING"]}
                    """.formatted(receiver.getAddress().getPort()))).andExpect(status().isOk());
            var alert = new AlertEventEntity();
            alert.setId(8003L); alert.setServerId(9001L); alert.setResourceType("SERVER");
            alert.setResourceId("9001"); alert.setResourceName("edge-1"); alert.setSeverity("CRITICAL");
            alert.setStatus("FIRING"); alert.setMessage("Lease fixture");
            webhookService.publishAlert(alert, "FIRING");
            oldWorker = java.util.concurrent.CompletableFuture.runAsync(deliveryService::deliverPending);
            assertTrue(oldRequestEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            String oldToken = jdbcTemplate.queryForObject("SELECT claim_token FROM automation_webhook_delivery", String.class);
            assertTrue(oldToken != null && !oldToken.isBlank());
            // Model a process pause exceeding the lease, while its HTTP response is still outstanding.
            jdbcTemplate.update("UPDATE automation_webhook_delivery SET claim_expires_at=?",
                    LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
            newWorker = java.util.concurrent.CompletableFuture.runAsync(deliveryService::deliverPending);
            assertTrue(newRequestEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertEquals(bodies.getFirst(), bodies.getLast());
            String newToken = jdbcTemplate.queryForObject("SELECT claim_token FROM automation_webhook_delivery", String.class);
            assertTrue(newToken != null && !newToken.equals(oldToken));
            var newerResult = jdbcTemplate.queryForMap("SELECT status,attempt_count,response_code,error_message,sent_at,next_attempt_at,updated_at,claim_token FROM automation_webhook_delivery");
            releaseOldRequest.countDown();
            oldWorker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(newerResult, jdbcTemplate.queryForMap("SELECT status,attempt_count,response_code,error_message,sent_at,next_attempt_at,updated_at,claim_token FROM automation_webhook_delivery"),
                    "Late HTTP success must not rewrite the newer worker's result or retry schedule");
            assertEquals("SENDING", jdbcTemplate.queryForObject("SELECT status FROM automation_webhook_delivery", String.class));
            releaseNewRequest.countDown();
            newWorker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM automation_webhook_delivery WHERE status='FAILED' AND attempt_count=2 AND response_code=503 AND sent_at IS NULL AND claim_token IS NULL", Integer.class));
        } finally {
            releaseOldRequest.countDown();
            releaseNewRequest.countDown();
            try {
                if (oldWorker != null) oldWorker.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (newWorker != null) newWorker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            finally { executor.shutdownNow(); }
        }
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

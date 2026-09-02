package com.devpilot.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devpilot.server.cicd.service.DeploymentWebhookClient;
import com.devpilot.server.cicd.service.DeploymentWebhookClient.DeploymentState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeploymentWebhookClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger deploymentListCalls = new AtomicInteger();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void coolifyUpdatesExactImageThenDeploys() throws Exception {
        DeploymentWebhookClient client = new DeploymentWebhookClient(objectMapper);
        String id = client.deploy("COOLIFY", "API", null,
                baseUrl + "/api/v1", "coolify-token", "app_42", "ghcr.io/acme/demo:sha-abcdef123456");

        assertEquals("coolify-deploy-1", id);
        assertEquals(2, requests.size());
        assertEquals("PATCH", requests.get(0).method());
        assertEquals("/api/v1/applications/app_42", requests.get(0).path());
        assertEquals("Bearer coolify-token", requests.get(0).authorization());
        JsonNode update = objectMapper.readTree(requests.get(0).body());
        assertEquals("ghcr.io/acme/demo", update.path("docker_registry_image_name").asText());
        assertEquals("sha-abcdef123456", update.path("docker_registry_image_tag").asText());
        assertEquals("POST", requests.get(1).method());
        assertEquals("/api/v1/deploy?uuid=app_42", requests.get(1).path());
        assertEquals("coolify build output", client.fetchLogs("COOLIFY", baseUrl, "coolify-token",
                "app_42", id));
        assertEquals("/api/v1/deployments/coolify-deploy-1", requests.get(2).path());
    }

    @Test
    void dokployUpdatesExactImageThenDeploys() throws Exception {
        DeploymentWebhookClient client = new DeploymentWebhookClient(objectMapper);
        String id = client.deploy("DOKPLOY", "API", null,
                baseUrl, "dokploy-token", "app-42", "registry.example/demo@sha256:" + "a".repeat(64));

        assertEquals("dokploy-deploy-1", id);
        assertEquals(4, requests.size());
        assertEquals("/api/application.update", requests.get(0).path());
        assertEquals("dokploy-token", requests.get(0).apiKey());
        JsonNode update = objectMapper.readTree(requests.get(0).body());
        assertEquals("app-42", update.path("applicationId").asText());
        assertTrue(update.path("dockerImage").asText().endsWith("a".repeat(64)));
        assertEquals("/api/deployment.all?applicationId=app-42", requests.get(1).path());
        assertEquals("/api/application.deploy", requests.get(2).path());
        assertEquals("/api/deployment.all?applicationId=app-42", requests.get(3).path());
        assertEquals("dokploy build output", client.fetchLogs("DOKPLOY", baseUrl, "dokploy-token",
                "app-42", id));
        assertEquals("/api/deployment.readLogs?deploymentId=dokploy-deploy-1&tail=10000", requests.get(4).path());
        assertEquals(DeploymentState.SUCCEEDED, client.fetchDeploymentState("DOKPLOY", baseUrl,
                "dokploy-token", "app-42", id));
        assertEquals("/api/deployment.all?applicationId=app-42", requests.get(5).path());
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            String path = exchange.getRequestURI().toString();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(exchange.getRequestMethod(), path, body,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-api-key")));
            String response = path.startsWith("/api/v1/deployments/")
                    ? "{\"logs\":\"coolify build output\"}"
                    : path.startsWith("/api/v1/deploy")
                    ? "{\"deployments\":[{\"deployment_uuid\":\"coolify-deploy-1\"}]}"
                    : path.startsWith("/api/deployment.all")
                    ? deploymentListCalls.incrementAndGet() == 1
                    ? "[{\"deploymentId\":\"dokploy-deploy-0\",\"status\":\"done\"}]"
                    : "[{\"deploymentId\":\"dokploy-deploy-1\",\"status\":\"done\"}]"
                    : path.startsWith("/api/deployment.readLogs") ? "{\"logs\":\"dokploy build output\"}"
                    : path.equals("/api/application.deploy") ? "{}" : "{}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record CapturedRequest(String method, String path, String body, String authorization, String apiKey) {}
}

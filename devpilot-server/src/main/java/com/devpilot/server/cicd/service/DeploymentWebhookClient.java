package com.devpilot.server.cicd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeploymentWebhookClient {
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            // Dokploy's local HTTP endpoint closes h2c upgrade attempts without
            // a response. Pin provider traffic to HTTP/1.1 for broad proxy and
            // self-hosted control-plane compatibility.
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public String deploy(String provider, String mode, String webhookUrl, String baseUrl,
                         String apiToken, String resourceId, String imageUri) {
        if ("API".equals(mode)) {
            return switch (provider) {
                case "COOLIFY" -> deployCoolify(baseUrl, apiToken, resourceId, imageUri);
                case "DOKPLOY" -> deployDokploy(baseUrl, apiToken, resourceId, imageUri);
                default -> throw new IllegalStateException("Unsupported deployment provider");
            };
        }
        trigger(provider, webhookUrl);
        return null;
    }

    public void trigger(String provider, String webhookUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "DevPilot/0.2 " + provider.toLowerCase() + "-deployment")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        send(provider, request);
    }

    public String fetchLogs(String provider, String baseUrl, String apiToken, String resourceId,
                            String providerDeploymentId) {
        if (baseUrl == null || apiToken == null || resourceId == null) return null;
        String root = stripApiSuffix(baseUrl);
        String response;
        if ("COOLIFY".equals(provider)) {
            if (providerDeploymentId == null || providerDeploymentId.isBlank()) return null;
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/v1/deployments/" + urlEncode(providerDeploymentId)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("User-Agent", "DevPilot/0.2 coolify-deployment-logs")
                    .GET().build();
            response = send("COOLIFY", request);
        } else if ("DOKPLOY".equals(provider)) {
            String endpoint = providerDeploymentId == null || providerDeploymentId.isBlank()
                    ? "/api/application.readLogs?applicationId=" + urlEncode(resourceId) + "&tail=10000&since=all"
                    : "/api/deployment.readLogs?deploymentId=" + urlEncode(providerDeploymentId) + "&tail=10000";
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-api-key", apiToken)
                    .header("User-Agent", "DevPilot/0.2 dokploy-deployment-logs")
                    .GET().build();
            response = send("DOKPLOY", request);
        } else {
            return null;
        }
        return extractLogs(response);
    }

    public DeploymentState fetchDeploymentState(String provider, String baseUrl, String apiToken,
                                                 String resourceId, String providerDeploymentId) {
        if (!"DOKPLOY".equals(provider) || baseUrl == null || apiToken == null
                || resourceId == null || providerDeploymentId == null || providerDeploymentId.isBlank()) {
            return DeploymentState.UNKNOWN;
        }
        String root = stripApiSuffix(baseUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/deployment.all?applicationId="
                        + urlEncode(resourceId)))
                .timeout(Duration.ofSeconds(15))
                .header("x-api-key", apiToken)
                .header("User-Agent", "DevPilot/0.2 dokploy-deployment-state")
                .GET().build();
        try {
            JsonNode deployments = objectMapper.readTree(send("DOKPLOY", request));
            if (!deployments.isArray()) return DeploymentState.UNKNOWN;
            for (JsonNode deployment : deployments) {
                if (!providerDeploymentId.equals(textOrNull(deployment, "deploymentId"))) continue;
                String status = textOrNull(deployment, "status");
                if (status == null) return DeploymentState.UNKNOWN;
                return switch (status.toLowerCase()) {
                    case "done", "success", "succeeded" -> DeploymentState.SUCCEEDED;
                    case "error", "failed", "cancelled" -> DeploymentState.FAILED;
                    default -> DeploymentState.PENDING;
                };
            }
            return DeploymentState.UNKNOWN;
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Unable to parse DOKPLOY deployment state", exception);
        }
    }

    private String deployCoolify(String baseUrl, String apiToken, String resourceId, String imageUri) {
        ImageReference image = ImageReference.parse(imageUri);
        String root = stripApiSuffix(baseUrl);
        String encodedId = urlEncode(resourceId);
        String updateBody = json(Map.of(
                "docker_registry_image_name", image.repository(),
                "docker_registry_image_tag", image.providerTag()));
        HttpRequest update = HttpRequest.newBuilder(URI.create(root + "/api/v1/applications/" + encodedId))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DevPilot/0.2 coolify-deployment")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(updateBody, StandardCharsets.UTF_8))
                .build();
        send("COOLIFY", update);

        HttpRequest deploy = HttpRequest.newBuilder(URI.create(root + "/api/v1/deploy?uuid=" + encodedId))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiToken)
                .header("User-Agent", "DevPilot/0.2 coolify-deployment")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        String response = send("COOLIFY", deploy);
        try {
            JsonNode deployments = objectMapper.readTree(response).path("deployments");
            return deployments.isArray() && !deployments.isEmpty()
                    ? textOrNull(deployments.get(0), "deployment_uuid") : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String deployDokploy(String baseUrl, String apiToken, String resourceId, String imageUri) {
        String root = stripApiSuffix(baseUrl);
        send("DOKPLOY", jsonPost(root + "/api/application.update", apiToken,
                Map.of("applicationId", resourceId, "dockerImage", imageUri)));
        String response = send("DOKPLOY", jsonPost(root + "/api/application.deploy", apiToken,
                Map.of("applicationId", resourceId)));
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            String id = textOrNull(rootNode, "deploymentId");
            if (id == null) id = textOrNull(rootNode, "deployment_id");
            return id == null ? awaitLatestDokployDeployment(root, apiToken, resourceId) : id;
        } catch (Exception ignored) {
            return awaitLatestDokployDeployment(root, apiToken, resourceId);
        }
    }

    private String awaitLatestDokployDeployment(String root, String apiToken, String resourceId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/deployment.all?applicationId="
                        + urlEncode(resourceId)))
                .timeout(Duration.ofSeconds(15))
                .header("x-api-key", apiToken)
                .header("User-Agent", "DevPilot/0.2 dokploy-deployment-state")
                .GET().build();
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                JsonNode deployments = objectMapper.readTree(send("DOKPLOY", request));
                if (deployments.isArray() && !deployments.isEmpty()) {
                    String id = textOrNull(deployments.get(0), "deploymentId");
                    if (id != null) return id;
                }
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DOKPLOY deployment lookup was interrupted", exception);
            } catch (Exception exception) {
                if (attempt == 4) throw new IllegalStateException("Unable to identify DOKPLOY deployment", exception);
            }
        }
        throw new IllegalStateException("DOKPLOY did not return a deployment identifier");
    }

    private HttpRequest jsonPost(String url, String apiToken, Map<String, String> body) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("x-api-key", apiToken)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DevPilot/0.2 dokploy-deployment")
                .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                .build();
    }

    private String send(String provider, HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(provider + " API returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(provider + " deployment was interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            String detail = exception.getMessage();
            if ((detail == null || detail.isBlank()) && exception.getCause() != null) {
                detail = exception.getCause().getMessage();
            }
            String suffix = detail == null || detail.isBlank() ? exception.getClass().getSimpleName()
                    : exception.getClass().getSimpleName() + ": " + detail;
            throw new IllegalStateException(provider + " deployment request failed (" + suffix + ")", exception);
        }
    }

    private String json(Map<String, String> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize deployment request", exception);
        }
    }

    private static String stripApiSuffix(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (result.endsWith("/api/v1")) result = result.substring(0, result.length() - 7);
        else if (result.endsWith("/api")) result = result.substring(0, result.length() - 4);
        return result;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private String extractLogs(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.isTextual()) return node.asText();
            for (String field : List.of("logs", "data", "output")) {
                JsonNode value = node.get(field);
                if (value != null && !value.isNull()) return value.isTextual() ? value.asText() : value.toPrettyString();
            }
            return node.toPrettyString();
        } catch (Exception ignored) {
            return response;
        }
    }

    private record ImageReference(String repository, String providerTag) {
        static ImageReference parse(String value) {
            int digest = value.lastIndexOf("@sha256:");
            if (digest > 0) {
                return new ImageReference(value.substring(0, digest), "sha256-" + value.substring(digest + 8));
            }
            int slash = value.lastIndexOf('/');
            int colon = value.lastIndexOf(':');
            if (colon <= slash) throw new IllegalStateException("Image reference must include an immutable tag or digest");
            return new ImageReference(value.substring(0, colon), value.substring(colon + 1));
        }
    }

    public enum DeploymentState {
        PENDING, SUCCEEDED, FAILED, UNKNOWN
    }
}

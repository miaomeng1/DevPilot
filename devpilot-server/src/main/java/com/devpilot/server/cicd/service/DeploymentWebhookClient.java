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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    public String deployPreview(String provider, String mode, String baseUrl, String apiToken,
                                String resourceId, int pullRequestId, String imageUri) {
        if (!"COOLIFY".equals(provider) || !"API".equals(mode)) {
            throw new IllegalStateException("Managed previews require COOLIFY API mode");
        }
        ImageReference image = ImageReference.parse(imageUri);
        String root = stripApiSuffix(baseUrl);
        String applicationEndpoint = root + "/api/v1/applications/" + urlEncode(resourceId);
        HttpRequest inspect = HttpRequest.newBuilder(URI.create(applicationEndpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiToken)
                .header("User-Agent", "DevPilot/0.2 coolify-preview-inspection")
                .GET().build();
        try {
            String configuredRepository = textOrNull(objectMapper.readTree(send("COOLIFY", inspect)),
                    "docker_registry_image_name");
            if (!image.repository().equals(configuredRepository)) {
                throw new IllegalStateException("Preview image repository does not match the Coolify application");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify Coolify preview image repository", exception);
        }
        String endpoint = root + "/api/v1/deploy?uuid=" + urlEncode(resourceId)
                + "&pull_request_id=" + pullRequestId + "&docker_tag=" + urlEncode(image.providerTag());
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiToken)
                .header("User-Agent", "DevPilot/0.2 coolify-preview-deployment")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return deploymentId(send("COOLIFY", request));
    }

    public void deletePreview(String provider, String mode, String baseUrl, String apiToken,
                              String resourceId, int pullRequestId) {
        if (!"COOLIFY".equals(provider) || !"API".equals(mode)) {
            throw new IllegalStateException("Managed previews require COOLIFY API mode");
        }
        String root = stripApiSuffix(baseUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/v1/applications/"
                        + urlEncode(resourceId) + "/previews/" + pullRequestId))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiToken)
                .header("User-Agent", "DevPilot/0.2 coolify-preview-cleanup")
                .DELETE().build();
        sendAllowNotFound("COOLIFY", request);
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

    public void syncCoolifyEnvironment(String baseUrl, String apiToken, String resourceId,
                                       Map<String, EnvironmentVariable> desired, Set<String> previouslyManagedKeys) {
        String root = stripApiSuffix(baseUrl);
        String endpoint = root + "/api/v1/applications/" + urlEncode(resourceId) + "/envs";
        HttpRequest listRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiToken)
                .header("User-Agent", "DevPilot/0.2 coolify-environment-sync")
                .GET().build();
        Map<String, String> current = new LinkedHashMap<>();
        try {
            JsonNode response = objectMapper.readTree(send("COOLIFY", listRequest));
            if (!response.isArray()) throw new IllegalStateException("COOLIFY env API returned an invalid response");
            for (JsonNode item : response) {
                String key = textOrNull(item, "key");
                String uuid = textOrNull(item, "uuid");
                if (key != null && uuid != null) current.put(key, uuid);
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse COOLIFY environment variables", exception);
        }

        for (Map.Entry<String, EnvironmentVariable> entry : desired.entrySet()) {
            EnvironmentVariable variable = entry.getValue();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", entry.getKey());
            body.put("value", variable.value());
            body.put("is_preview", false);
            body.put("is_literal", true);
            body.put("is_multiline", variable.value().contains("\n"));
            body.put("is_shown_once", variable.secret());
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DevPilot/0.2 coolify-environment-sync")
                    .method(current.containsKey(entry.getKey()) ? "PATCH" : "POST",
                            HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                    .build();
            send("COOLIFY", request);
        }
        for (String removedKey : previouslyManagedKeys) {
            if (desired.containsKey(removedKey)) continue;
            String uuid = current.get(removedKey);
            if (uuid == null) continue;
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/" + urlEncode(uuid)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("User-Agent", "DevPilot/0.2 coolify-environment-sync")
                    .DELETE().build();
            send("COOLIFY", request);
        }
    }

    public DeploymentState fetchDeploymentState(String provider, String baseUrl, String apiToken,
                                                 String resourceId, String providerDeploymentId) {
        if (baseUrl == null || apiToken == null || resourceId == null
                || providerDeploymentId == null || providerDeploymentId.isBlank()) {
            return DeploymentState.UNKNOWN;
        }
        String root = stripApiSuffix(baseUrl);
        if ("COOLIFY".equals(provider)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/v1/deployments/"
                            + urlEncode(providerDeploymentId)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("User-Agent", "DevPilot/0.2 coolify-deployment-state")
                    .GET().build();
            try {
                String status = textOrNull(objectMapper.readTree(send("COOLIFY", request)), "status");
                return deploymentState(status);
            } catch (Exception exception) {
                if (exception instanceof IllegalStateException state) throw state;
                throw new IllegalStateException("Unable to parse COOLIFY deployment state", exception);
            }
        }
        if (!"DOKPLOY".equals(provider)) return DeploymentState.UNKNOWN;
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
                return deploymentState(status);
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
        return deploymentId(response);
    }

    private String deployDokploy(String baseUrl, String apiToken, String resourceId, String imageUri) {
        String root = stripApiSuffix(baseUrl);
        send("DOKPLOY", jsonPost(root + "/api/application.update", apiToken,
                Map.of("applicationId", resourceId, "dockerImage", imageUri)));
        String previousDeploymentId = latestDokployDeployment(root, apiToken, resourceId);
        String response = send("DOKPLOY", jsonPost(root + "/api/application.deploy", apiToken,
                Map.of("applicationId", resourceId)));
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            String id = textOrNull(rootNode, "deploymentId");
            if (id == null) id = textOrNull(rootNode, "deployment_id");
            return id == null ? awaitLatestDokployDeployment(root, apiToken, resourceId, previousDeploymentId) : id;
        } catch (Exception ignored) {
            return awaitLatestDokployDeployment(root, apiToken, resourceId, previousDeploymentId);
        }
    }

    private String latestDokployDeployment(String root, String apiToken, String resourceId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/deployment.all?applicationId="
                        + urlEncode(resourceId)))
                .timeout(Duration.ofSeconds(15))
                .header("x-api-key", apiToken)
                .header("User-Agent", "DevPilot/0.2 dokploy-deployment-state")
                .GET().build();
        try {
            JsonNode deployments = objectMapper.readTree(send("DOKPLOY", request));
            return deployments.isArray() && !deployments.isEmpty()
                    ? textOrNull(deployments.get(0), "deploymentId") : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String awaitLatestDokployDeployment(String root, String apiToken, String resourceId,
                                                String previousDeploymentId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                String id = latestDokployDeployment(root, apiToken, resourceId);
                if (id != null && !id.equals(previousDeploymentId)) return id;
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DOKPLOY deployment lookup was interrupted", exception);
            } catch (Exception exception) {
                if (attempt == 9) throw new IllegalStateException("Unable to identify DOKPLOY deployment", exception);
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

    private void sendAllowNotFound(String provider, HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if ((response.statusCode() < 200 || response.statusCode() >= 300) && response.statusCode() != 404) {
                throw new IllegalStateException(provider + " API returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(provider + " cleanup was interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException(provider + " cleanup request failed", exception);
        }
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize deployment request", exception);
        }
    }

    private String deploymentId(String response) {
        try {
            JsonNode deployments = objectMapper.readTree(response).path("deployments");
            return deployments.isArray() && !deployments.isEmpty()
                    ? textOrNull(deployments.get(0), "deployment_uuid") : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static DeploymentState deploymentState(String status) {
        if (status == null) return DeploymentState.UNKNOWN;
        String normalized = status.toLowerCase();
        if (Set.of("done", "success", "succeeded", "finished").contains(normalized)) {
            return DeploymentState.SUCCEEDED;
        }
        if (normalized.contains("fail") || normalized.contains("error") || normalized.contains("cancel")) {
            return DeploymentState.FAILED;
        }
        return DeploymentState.PENDING;
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

    public record EnvironmentVariable(String value, boolean secret) { }
}

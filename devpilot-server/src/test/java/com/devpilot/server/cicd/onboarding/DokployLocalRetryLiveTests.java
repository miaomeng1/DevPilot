package com.devpilot.server.cicd.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in local lab only. Keeps its uniquely named, never-deployed application for inspection. */
@EnabledIfEnvironmentVariable(named = "DEVPILOT_LOCAL_RETRY_LIVE", matches = "true")
class DokployLocalRetryLiveTests {
    private static final String ROOT = "http://127.0.0.1:19000";

    @Test void realConfigurationSurvivesRetryAndRejectsExternalConflicts() throws Exception {
        String key = Files.readString(Path.of(required("DEVPILOT_LOCAL_DOKPLOY_KEY_FILE"))).trim();
        String project = required("DEVPILOT_LOCAL_DOKPLOY_PROJECT");
        String environment = required("DEVPILOT_LOCAL_DOKPLOY_ENVIRONMENT");
        var http = new RestrictedHttp();
        var client = new ProviderOnboardingClient(http);
        var request = mock(OnboardingRequest.class);
        when(request.deploymentProvider()).thenReturn("DOKPLOY");
        when(request.providerBaseUrl()).thenReturn(ROOT);
        when(request.providerApiToken()).thenReturn(key);
        when(request.projectId()).thenReturn(project);
        when(request.environmentId()).thenReturn(environment);
        when(request.imageRepository()).thenReturn("ghcr.io/example/never-deployed");
        when(request.containerPort()).thenReturn(8080);
        when(request.hostPort()).thenReturn(28089);
        when(request.environmentValues()).thenReturn(Map.of("RETRY_FIXTURE", "preserve-me"));
        String job = System.getenv("DEVPILOT_LOCAL_RETRY_JOB_ID");
        if (job == null || job.isBlank()) job = UUID.randomUUID().toString();
        UUID.fromString(job);
        var app = client.ensureApplication(request, "retry-live", job);
        http.ownedId = app.id();
        System.out.println("Local retry fixture retained: applicationId=" + app.id() + ", runtimeKey=" + app.runtimeKey());
        var initial = client.verify("DOKPLOY", ROOT, key, app.id());
        assertTrue(initial.path("appName").asText().startsWith("dp-retry-live-"), "Scratch application required");
        assertEquals("DevPilot onboarding " + job, initial.path("description").asText());
        assertTrue(initial.path("ports").isArray() && initial.path("ports").isEmpty(),
                "Resume only before port creation; use a fresh job for a completed fixture");
        assertEquals(app, client.ensureApplication(request, "retry-live", job));
        assertTrue(http.calls.stream().filter(p -> p.equals("/api/application.create")).count() <= 1);

        // All values below are synthetic. No image pull, build, or deployment endpoint is allowed.
        String image = "ghcr.io/example/never-deployed@sha256:" + "a".repeat(64);
        http.call("DOKPLOY", key, "POST", ROOT + "/api/application.saveDockerProvider",
                Map.of("applicationId", app.id(), "dockerImage", image, "registryUrl", "https://ghcr.io",
                        "username", "synthetic-only", "password", "synthetic-only"));
        http.call("DOKPLOY", key, "POST", ROOT + "/api/application.saveEnvironment",
                Map.of("applicationId", app.id(), "env", "", "buildArgs", "ARG=preserved",
                        "buildSecrets", "SECRET=synthetic-only", "createEnvFile", true));
        http.losePortResponse = true;
        assertThrows(OnboardingHttpClient.RemoteFailure.class, () -> client.configure(request, app.id()));
        client.configure(request, app.id());
        JsonNode after = client.verify("DOKPLOY", ROOT, key, app.id());
        assertEquals(image, after.path("dockerImage").asText());
        assertEquals("ARG=preserved", after.path("buildArgs").asText());
        assertEquals("SECRET=synthetic-only", after.path("buildSecrets").asText());
        assertTrue(after.path("createEnvFile").asBoolean());
        assertEquals("RETRY_FIXTURE='preserve-me'\n", after.path("env").asText());
        assertEquals(1, after.path("ports").size());
        long writes = http.calls.stream().filter(p -> p.equals("/api/application.saveEnvironment")).count();
        client.configure(request, app.id());
        assertEquals(writes, http.calls.stream().filter(p -> p.equals("/api/application.saveEnvironment")).count());
        assertEquals(1, http.calls.stream().filter(p -> p.equals("/api/port.create")).count());

        when(request.containerPort()).thenReturn(9090);
        int before = http.calls.size();
        assertThrows(IllegalArgumentException.class, () -> client.configure(request, app.id()));
        assertEquals(List.of("/api/application.one"), http.calls.subList(before, http.calls.size()));
        when(request.containerPort()).thenReturn(8080);
        when(request.environmentValues()).thenReturn(Map.of("RETRY_FIXTURE", "different"));
        before = http.calls.size();
        assertThrows(IllegalArgumentException.class, () -> client.configure(request, app.id()));
        assertEquals(List.of("/api/application.one"), http.calls.subList(before, http.calls.size()));
        System.out.println("PASS: real Dokploy persisted image/build settings/env; lost port response reconciled; conflicting retries made no writes. No deployment endpoint called.");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static class RestrictedHttp extends OnboardingHttpClient {
        final List<String> calls = new ArrayList<>();
        String ownedId;
        boolean losePortResponse;
        RestrictedHttp() { super(new ObjectMapper()); }

        @Override public JsonNode call(String provider, String token, String method, String url, Object body) {
            var uri = java.net.URI.create(url);
            if (!url.startsWith(ROOT + "/api/")) throw new IllegalArgumentException("Local fixture endpoint only");
            String path = uri.getPath();
            boolean read = "GET".equals(method) && List.of("/api/project.one", "/api/application.one").contains(path);
            boolean create = "POST".equals(method) && path.equals("/api/application.create") && ownedId == null;
            boolean configure = "POST".equals(method) && ownedId != null
                    && List.of("/api/application.saveDockerProvider", "/api/application.saveEnvironment", "/api/port.create").contains(path)
                    && body instanceof Map<?, ?> map && ownedId.equals(map.get("applicationId"));
            if (!read && !create && !configure) throw new IllegalArgumentException("Operation outside scratch application scope");
            calls.add(path);
            JsonNode response = super.call(provider, token, method, url, body);
            if (losePortResponse && path.equals("/api/port.create")) {
                losePortResponse = false;
                throw new RemoteFailure("Synthetic lost response after real port save", 0);
            }
            return response;
        }
    }
}

package com.devpilot.server.cicd.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderOnboardingRetryTests {
    @Test void portConflictsAndUnreadableMappingsStopBeforeAnyWrite() throws Exception {
        var json = new ObjectMapper();
        String mapping = """
                {"publishedPort":18088,"targetPort":8080,"protocol":"tcp","publishMode":"ingress"}
                """;
        for (String ports : java.util.List.of("null", "{}", "[{}]",
                "[" + mapping.replace("8080", "9090") + "]",
                "[" + mapping.replace("tcp", "udp") + "]",
                "[" + mapping.replace("ingress", "host") + "]",
                "[{\"publishedPort\":18088,\"targetPort\":8080}]",
                "[" + mapping + "," + mapping + "]")) {
            var http = mock(OnboardingHttpClient.class);
            when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null))
                    .thenReturn(json.readTree("{\"applicationId\":\"app1\",\"ports\":" + ports + "}"));
            var error = assertThrows(IllegalArgumentException.class, () -> new ProviderOnboardingClient(http).configure(request(), "app1"));
            assertTrue(error.getMessage().contains("未修改配置"));
            verify(http).call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null);
            verifyNoMoreInteractions(http);
        }
    }

    @Test void lostPortCreationResponseIsReconciledWithoutDuplicateCreation() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var json = new ObjectMapper();
        var app = json.readTree("{\"applicationId\":\"app1\",\"ports\":[]}");
        when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null)).thenReturn(app);
        when(http.call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/port.create"), any()))
                .thenAnswer(call -> {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) app.path("ports")).add(json.valueToTree(call.getArgument(4)));
                    throw new IllegalStateException("Synthetic lost response after port was saved");
                });
        var client = new ProviderOnboardingClient(http);
        assertThrows(IllegalStateException.class, () -> client.configure(request(), "app1"));
        client.configure(request(), "app1");
        verify(http, times(1)).call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/port.create"), any());
        assertEquals(1, app.path("ports").size());
        assertEquals(java.util.List.of("GET", "POST", "POST", "GET", "POST", "GET"),
                mockingDetails(http).getInvocations().stream().map(call -> call.getArgument(2)).toList());
    }

    @Test void environmentWritePreservesBuildSettingsAndIdenticalRetrySkipsIt() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var request = request();
        when(request.environmentValues()).thenReturn(Map.of("PUBLIC_URL", "https://example.invalid"));
        var app = new ObjectMapper().readTree("""
          {"applicationId":"app1","env":"","buildArgs":"ARG=keep","buildSecrets":"SECRET=synthetic",
           "createEnvFile":true,"ports":[{"publishedPort":18088,"targetPort":8080,"protocol":"tcp","publishMode":"ingress"}]}
          """);
        when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null)).thenReturn(app);
        var client = new ProviderOnboardingClient(http);
        client.configure(request, "app1");
        verify(http).call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/application.saveEnvironment"), argThat(body ->
                body instanceof Map<?, ?> map && "ARG=keep".equals(map.get("buildArgs"))
                && "SECRET=synthetic".equals(map.get("buildSecrets")) && Boolean.TRUE.equals(map.get("createEnvFile"))
                && "PUBLIC_URL='https://example.invalid'\n".equals(map.get("env"))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) app).put("env", "PUBLIC_URL='https://example.invalid'\n");
        clearInvocations(http);
        client.configure(request, "app1");
        verify(http, never()).call(any(), any(), any(), eq("https://dokploy.example/api/application.saveEnvironment"), any());
    }

    @Test void conflictingOrUnreadableEnvironmentStopsBeforeAnyRemoteWriteWithoutExposingValues() throws Exception {
        for (String remote : java.util.List.of(
                "{\"applicationId\":\"app1\",\"env\":\"OTHER=private-fixture\"}",
                "{\"applicationId\":\"app1\"}",
                "{\"applicationId\":\"app1\",\"env\":{\"unexpected\":true}}",
                "{\"applicationId\":\"app1\",\"env\":\"\",\"buildArgs\":{},\"buildSecrets\":\"\",\"createEnvFile\":false}",
                "{\"applicationId\":\"app1\",\"env\":\"\"}")) {
            var http = mock(OnboardingHttpClient.class);
            var request = request();
            when(request.environmentValues()).thenReturn(Map.of("MY_KEY", "new-private-fixture"));
            when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null))
                    .thenReturn(new ObjectMapper().readTree(remote));
            var error = assertThrows(IllegalArgumentException.class, () -> new ProviderOnboardingClient(http).configure(request, "app1"));
            assertFalse(error.getMessage().contains("private-fixture"));
            verify(http).call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null);
            verifyNoMoreInteractions(http);
        }
    }

    private OnboardingRequest request() {
        var request = mock(OnboardingRequest.class);
        when(request.deploymentProvider()).thenReturn("DOKPLOY");
        when(request.providerBaseUrl()).thenReturn("https://dokploy.example");
        when(request.providerApiToken()).thenReturn("synthetic-key");
        when(request.imageRepository()).thenReturn("ghcr.io/example/demo");
        when(request.registryUsername()).thenReturn("example");
        when(request.registryPassword()).thenReturn("synthetic-pull-password");
        when(request.hostPort()).thenReturn(18088);
        when(request.containerPort()).thenReturn(8080);
        return request;
    }

    @Test void retriesPreserveObservedDigestAndExistingPortWithoutDeploymentOrResourceCreation() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var app = new ObjectMapper().readTree("""
          {"applicationId":"app1","dockerImage":"ghcr.io/example/demo@sha256:%s",
           "ports":[{"publishedPort":18088,"targetPort":8080,"protocol":"tcp","publishMode":"ingress"}]}
          """.formatted("a".repeat(64)));
        when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null)).thenReturn(app);
        var client = new ProviderOnboardingClient(http);
        for (int i = 0; i < 2; i++) client.configure(request(), "app1");
        verify(http, times(4)).call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null);
        verify(http, times(2)).call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/application.saveDockerProvider"), argThat(body ->
                    body instanceof Map<?, ?> map && app.path("dockerImage").asText().equals(map.get("dockerImage"))
                    && !map.containsKey("registryId") && "synthetic-pull-password".equals(map.get("password"))));
        assertEquals(java.util.List.of("GET", "POST", "GET", "GET", "POST", "GET"),
                mockingDetails(http).getInvocations().stream().map(call -> call.getArgument(2)).toList());
        verifyNoMoreInteractions(http);
    }

    @Test void onlyBlankImagesGetInitialPlaceholderAndWrongResourceCannotBeWritten() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var json = new ObjectMapper();
        when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null))
                .thenReturn(json.readTree("{\"applicationId\":\"app1\",\"ports\":[]}"));
        var client = new ProviderOnboardingClient(http);
        client.configure(request(), "app1");
        verify(http).call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/application.saveDockerProvider"), argThat(body ->
                body instanceof Map<?, ?> map && "ghcr.io/example/demo:pending-first-release".equals(map.get("dockerImage"))));
        clearInvocations(http);
        when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null))
                .thenReturn(json.readTree("{\"applicationId\":\"other\"}"));
        assertThrows(IllegalArgumentException.class, () -> client.configure(request(), "app1"));
        verify(http).call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null);
        verifyNoMoreInteractions(http);
    }

    @Test void retryAfterLostWriteResponseRereadsChangedRemoteImage() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var json = new ObjectMapper();
        String digest = "ghcr.io/example/demo@sha256:" + "b".repeat(64);
        var first = json.readTree("""
                {"applicationId":"app1","ports":[{"publishedPort":18088,"targetPort":8080,"protocol":"tcp","publishMode":"ingress"}]}
                """);
        var changed = first.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("dockerImage", digest);
        when(http.call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null))
                .thenReturn(first, changed);
        when(http.call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/application.saveDockerProvider"), any()))
                .thenThrow(new IllegalStateException("Simulated lost response after remote write")).thenReturn(json.createObjectNode());
        var client = new ProviderOnboardingClient(http);
        assertThrows(IllegalStateException.class, () -> client.configure(request(), "app1"));
        client.configure(request(), "app1");
        verify(http, times(3)).call("DOKPLOY", "synthetic-key", "GET", "https://dokploy.example/api/application.one?applicationId=app1", null);
        verify(http).call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/application.saveDockerProvider"), argThat(body ->
                body instanceof Map<?, ?> map && digest.equals(map.get("dockerImage"))));
        verify(http).call(eq("DOKPLOY"), eq("synthetic-key"), eq("POST"), eq("https://dokploy.example/api/application.saveDockerProvider"), argThat(body ->
                body instanceof Map<?, ?> map && "ghcr.io/example/demo:pending-first-release".equals(map.get("dockerImage"))));
        verifyNoMoreInteractions(http);
    }
}

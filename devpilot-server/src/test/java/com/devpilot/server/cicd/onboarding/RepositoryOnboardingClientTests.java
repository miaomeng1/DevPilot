package com.devpilot.server.cicd.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwebpp.crypto.TweetNaclFast;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.junit.jupiter.api.Test;

class RepositoryOnboardingClientTests {
    @Test
    void githubInspectionStopsAtLowQuotaOrKnownReadOnlyAccess() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var json = new ObjectMapper();
        var client = new RepositoryOnboardingClient(http);
        when(http.call("GITHUB", "token", "GET", "https://api.github.com/rate_limit", null))
                .thenReturn(json.readTree("{\"resources\":{\"core\":{\"remaining\":10}}}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> client.inspect("GITHUB", "https://github.com/acme/demo", "token")).getMessage().contains("配额"));
        verify(http, never()).call("GITHUB", "token", "GET", "https://api.github.com/repos/acme/demo", null);
        when(http.call("GITHUB", "token", "GET", "https://api.github.com/rate_limit", null))
                .thenReturn(json.readTree("{\"resources\":{\"core\":{\"remaining\":5000}}}"));
        when(http.call("GITHUB", "token", "GET", "https://api.github.com/repos/acme/demo", null))
                .thenReturn(json.readTree("{\"permissions\":{\"push\":false}}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> client.inspect("GITHUB", "https://github.com/acme/demo", "token")).getMessage().contains("写入权限"));
        verify(http, never()).optional(anyString(), anyString(), anyString());
    }

    @Test
    void portPreflightRejectsCollisionOnSelectedServerOnly() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var json = new ObjectMapper();
        var request = mock(OnboardingRequest.class);
        when(request.deploymentProvider()).thenReturn("DOKPLOY");
        when(request.providerBaseUrl()).thenReturn("https://deploy.example");
        when(request.providerApiToken()).thenReturn("key");
        when(request.providerServerId()).thenReturn("");
        when(request.hostPort()).thenReturn(18088);
        when(http.call("DOKPLOY", "key", "GET", "https://deploy.example/api/project.all", null))
                .thenReturn(json.readTree("[{\"environments\":[{\"applications\":[{\"applicationId\":\"a\",\"serverId\":null}]}]}]"));
        when(http.call("DOKPLOY", "key", "GET", "https://deploy.example/api/application.one?applicationId=a", null))
                .thenReturn(json.readTree("{\"applicationId\":\"a\",\"ports\":[{\"publishedPort\":18088}]}"));
        var client = new ProviderOnboardingClient(http);
        assertThrows(IllegalArgumentException.class, () -> client.checkPublishedPort(request));
        when(request.providerServerId()).thenReturn("other-server");
        assertDoesNotThrow(() -> client.checkPublishedPort(request));
        verify(http, never()).call(eq("DOKPLOY"), anyString(), eq("POST"), anyString(), any());
    }

    @Test
    void dokployCreateProjectUnwrapsRealApiEnvelope() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var json = new ObjectMapper();
        var request = mock(OnboardingRequest.class);
        when(request.deploymentProvider()).thenReturn("DOKPLOY");
        when(request.providerBaseUrl()).thenReturn("https://deploy.example");
        when(request.providerApiToken()).thenReturn("key");
        when(http.call("DOKPLOY", "key", "GET", "https://deploy.example/api/project.all", null))
                .thenReturn(json.readTree("[]"));
        when(http.call(eq("DOKPLOY"), eq("key"), eq("POST"), eq("https://deploy.example/api/project.create"), any()))
                .thenReturn(json.readTree("{\"project\":{\"projectId\":\"p\"},\"environment\":{\"environmentId\":\"e\"}}"));
        when(http.call("DOKPLOY", "key", "GET", "https://deploy.example/api/project.one?projectId=p", null))
                .thenReturn(json.readTree("{\"environments\":[{\"name\":\"production\",\"environmentId\":\"e\"}]}"));
        var target = new ProviderOnboardingClient(http).ensureDedicatedProject(request, "demo", "12345678-job");
        assertEquals("p", target.projectId());
        assertEquals("e", target.environmentId());
    }

    @Test
    void sealedSecretsUseEphemeralKeysAndCanBeOpened() {
        var recipient = TweetNaclFast.Box.keyPair();
        String key = Base64.getEncoder().encodeToString(recipient.getPublicKey());
        String first = GithubSecretBox.seal(key, "中文 secret with spaces");
        assertNotEquals(first, GithubSecretBox.seal(key, "中文 secret with spaces"));
        byte[] sealed = Base64.getDecoder().decode(first);
        byte[] ephemeral = Arrays.copyOfRange(sealed, 0, 32);
        var hash = new Blake2bDigest(192);
        hash.update(ephemeral, 0, 32); hash.update(recipient.getPublicKey(), 0, 32);
        byte[] nonce = new byte[24]; hash.doFinal(nonce, 0);
        byte[] plaintext = new TweetNaclFast.Box(ephemeral, recipient.getSecretKey())
                .open(Arrays.copyOfRange(sealed, 32, sealed.length), nonce);
        assertEquals("中文 secret with spaces", new String(plaintext, StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> GithubSecretBox.seal("AA==", "secret"));
    }

    @Test
    void gitlabExistingIncludesStagesCommentsAndReferencesRemainUnchanged() {
        String original = "# user's configuration\ninclude:\n  - local: '.shared.yml'\nstages: [lint, ship]\n"
                + "lint:\n  script: !reference [.base, script]\n";
        String result = RepositoryOnboardingClient.withGitlabInclude(original, ".devpilot/blog.yml");
        assertTrue(result.startsWith(original));
        assertTrue(result.contains("stage: .post"));
        assertTrue(result.contains("strategy: depend"));
        assertEquals(result, RepositoryOnboardingClient.withGitlabInclude(result, ".devpilot/blog.yml"));
        assertFalse(result.contains("stages: [test"));
    }

    @Test
    void githubInspectionRejectsOtherOriginsBeforeSendingToken() {
        var http = mock(OnboardingHttpClient.class);
        var client = new RepositoryOnboardingClient(http);
        assertThrows(IllegalArgumentException.class, () -> client.inspect("GITHUB", "https://untrusted.example/a/b", "token"));
        verifyNoInteractions(http);
    }

    @Test
    void providerDiscoveryReturnsOnlyPublicMetadata() throws Exception {
        var http = mock(OnboardingHttpClient.class);
        var json = new ObjectMapper();
        when(http.call("DOKPLOY", "key", "GET", "https://deploy.example/api/project.all", null))
                .thenReturn(json.readTree("""
                [{"projectId":"p","name":"Personal","env":"SECRET=do-not-return",
                 "environments":[{"environmentId":"e","name":"production","applications":[{"password":"hidden"}]}]}]
                """));
        when(http.call("DOKPLOY", "key", "GET", "https://deploy.example/api/server.all", null)).thenReturn(json.readTree("[]"));
        var result = new ProviderOnboardingClient(http).discover("DOKPLOY", "https://deploy.example/api", "key");
        assertEquals("Personal / production", result.targets().getFirst().label());
        assertFalse(json.writeValueAsString(result).contains("do-not-return"));
    }
}

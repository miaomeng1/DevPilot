package com.devpilot.server.cicd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GithubRunClientTests {
    private static final String TOKEN = "fixture_token_only";
    private GithubRunEvidence.State observe(GithubRunClient client) {
        return client.observe("https://github.com/acme/demo", "build:github-42-2", "a".repeat(40), "main", TOKEN);
    }
    private GithubRunClient client(int status, Map<String, List<String>> headers, String body) {
        return new GithubRunClient(new ObjectMapper(), request -> {
            assertEquals("GET", request.method());
            assertEquals("https://api.github.com/repos/acme/demo/actions/runs/42/attempts/2", request.uri().toString());
            assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElseThrow());
            assertEquals(15, request.timeout().orElseThrow().toSeconds());
            return CompletableFuture.completedFuture(new GithubRunClient.Response(status, HttpHeaders.of(headers, (a,b) -> true), body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        });
    }
    @Test void errorsNeverReflectRemoteBodyOrTokenAndRedirectsAreNotSuccess() {
        for (int status : new int[]{301, 302, 401, 403, 404, 500}) {
            var error = assertThrows(GithubRunClient.Failure.class, () -> observe(client(status, Map.of(), TOKEN)));
            assertFalse(error.getMessage().contains(TOKEN));
            assertNull(error.getCause());
        }
    }
    @Test void rateLimitReturnsBoundedBackoffWithoutRetrying() {
        var error = assertThrows(GithubRunClient.Failure.class, () -> observe(client(403,
                Map.of("X-RateLimit-Remaining", List.of("0"), "Retry-After", List.of("999999")), "")));
        assertEquals("RATE_LIMITED", error.code); assertEquals(3600, error.retryAfterSeconds);
        assertEquals("RATE_LIMITED", assertThrows(GithubRunClient.Failure.class,
                () -> observe(client(429, Map.of(), ""))).code);
    }
    @Test void malformedOrMismatchedJsonIsNotTerminalEvidence() {
        for (String body : new String[]{"{", "{}", "null", "[]"}) {
            assertEquals("INVALID_OR_MISMATCHED_EVIDENCE", assertThrows(GithubRunClient.Failure.class,
                    () -> observe(client(200, Map.of(), body))).code);
        }
    }
    @Test void validAttemptIsEvaluatedWithoutGrantingReleaseEligibility() {
        String body = """
                {"id":42,"run_attempt":2,"head_sha":"%s","head_branch":"main","event":"push",
                 "repository":{"full_name":"acme/demo"},"head_repository":{"full_name":"acme/demo"},
                 "status":"completed","conclusion":"success"}
                """.formatted("a".repeat(40));
        assertEquals(GithubRunEvidence.State.SUCCESS_AWAITING_BUILD_EVIDENCE, observe(client(200, Map.of(), body)));
        assertEquals(GithubRunEvidence.State.FAILED, observe(client(200, Map.of(), body.replace("success", "failure"))));
    }
    @Test void interruptedRequestCancelsOutstandingWorkAndPreservesInterrupt() {
        var pending = new CompletableFuture<GithubRunClient.Response>();
        var client = new GithubRunClient(new ObjectMapper(), request -> pending);
        Thread.currentThread().interrupt();
        try {
            assertEquals("INTERRUPTED", assertThrows(GithubRunClient.Failure.class, () -> observe(client)).code);
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(pending.isCancelled());
        } finally { Thread.interrupted(); }
    }
    @Test void responseLimitCancelsSubscriptionBeforeAccumulatingOversizedChunk() {
        var body = new GithubRunClient.LimitedBody();
        boolean[] cancelled = {false};
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { cancelled[0] = true; }
        });
        body.onNext(List.of(ByteBuffer.allocate(GithubRunClient.LimitedBody.LIMIT + 1)));
        assertTrue(cancelled[0]);
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }
}

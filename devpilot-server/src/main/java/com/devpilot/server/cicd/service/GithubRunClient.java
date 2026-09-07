package com.devpilot.server.cicd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Read-only attempt lookup. Credentials are used only for this request. */
@Component
public class GithubRunClient {
    private final ObjectMapper json;
    private final Transport transport;
    record Response(int status, HttpHeaders headers, byte[] body) {}
    interface Transport { CompletableFuture<Response> send(HttpRequest request); }

    @Autowired
    public GithubRunClient(ObjectMapper json) {
        this(json, productionTransport());
    }
    GithubRunClient(ObjectMapper json, Transport transport) { this.json = json; this.transport = transport; }

    private static Transport productionTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var call = client.sendAsync(request, info -> new LimitedBody());
            var result = call.thenApply(r -> new Response(r.statusCode(), r.headers(), r.body()));
            result.whenComplete((value, error) -> { if (result.isCancelled()) call.cancel(true); });
            return result;
        };
    }

    public GithubRunEvidence.State observe(String repositoryUrl, String externalRunId, String commit,
                                            String branch, String token) {
        var identity = GithubRunEvidence.identity(repositoryUrl, externalRunId, commit, branch);
        if (token == null || !token.matches("[A-Za-z0-9_]{10,512}")) throw new Failure("CREDENTIAL_REQUIRED", 0);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com" + identity.apiPath()))
                .GET().timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "DevPilot-run-observer")
                .header("Authorization", "Bearer " + token).build();
        CompletableFuture<Response> pending = null;
        try {
            pending = transport.send(request);
            Response response = pending.get(20, TimeUnit.SECONDS);
            if (response.status == 429 || response.status == 403 &&
                    (response.headers.firstValue("X-RateLimit-Remaining").orElse("").equals("0")
                            || response.headers.firstValue("Retry-After").isPresent())) {
                int retry = 60;
                try { retry = Math.max(60, Math.min(3600, Integer.parseInt(response.headers.firstValue("Retry-After").orElse("60")))); }
                catch (NumberFormatException ignored) { }
                throw new Failure("RATE_LIMITED", retry);
            }
            if (response.status == 401 || response.status == 403) throw new Failure("CREDENTIAL_OR_PERMISSION", 0);
            if (response.status == 404) throw new Failure("RUN_NOT_ACCESSIBLE", 0);
            if (response.status != 200) throw new Failure("REMOTE_UNAVAILABLE", 60);
            if (response.body.length > LimitedBody.LIMIT) throw new Failure("INVALID_RESPONSE", 0);
            try { return GithubRunEvidence.evaluate(identity, json.readTree(response.body)); }
            catch (Exception ignored) { throw new Failure("INVALID_OR_MISMATCHED_EVIDENCE", 0); }
        } catch (Failure failure) { throw failure; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure("INTERRUPTED", 0);
        } catch (Exception ignored) { throw new Failure("NETWORK_OR_TIMEOUT", 60); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    public static class Failure extends RuntimeException {
        public final String code;
        public final int retryAfterSeconds;
        Failure(String code, int retryAfterSeconds) { super(code); this.code = code; this.retryAfterSeconds = retryAfterSeconds; }
    }

    // Bound accumulation before JSON parsing; deadline also covers body completion.
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        static final int LIMIT = 1024 * 1024;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > LIMIT - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Response too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

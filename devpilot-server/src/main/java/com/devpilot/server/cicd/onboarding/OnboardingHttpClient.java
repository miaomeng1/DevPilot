package com.devpilot.server.cicd.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OnboardingHttpClient {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();

    public JsonNode call(String provider, String token, String method, String url, Object body) {
        return call(provider, token, method, url, body, false);
    }

    public JsonNode optional(String provider, String token, String url) {
        return call(provider, token, "GET", url, null, true);
    }

    private JsonNode call(String provider, String token, String method, String url, Object body, boolean allowMissing) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "DevPilot-onboarding/1")
                    .header("Accept", "application/json").header("Content-Type", "application/json");
            if ("GITLAB".equals(provider)) builder.header("PRIVATE-TOKEN", token);
            else if ("DOKPLOY".equals(provider)) builder.header("x-api-key", token);
            else builder.header("Authorization", "Bearer " + token);
            if ("GITHUB".equals(provider)) builder.header("X-GitHub-Api-Version", "2022-11-28");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (allowMissing && response.statusCode() == 404) return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Upstream bodies and URLs can contain secrets. Never return them to audit/UI logs.
                throw new RemoteFailure(provider + " " + method + " HTTP " + response.statusCode()
                        + "；请检查授权范围、资源访问权和平台版本", response.statusCode());
            }
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        } catch (RemoteFailure exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteFailure(provider + " 请求中断；请重试当前步骤", 0);
        } catch (Exception exception) {
            throw new RemoteFailure(provider + " 网络或响应异常；远端可能已执行，请重试以核对现有资源", 0);
        }
    }

    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    public static String origin(String url, boolean requireHttps) {
        URI uri = URI.create(url);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || (!requireHttps && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("请输入无凭据的有效 " + (requireHttps ? "HTTPS" : "HTTP(S)") + " 地址");
        }
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    public static class RemoteFailure extends RuntimeException {
        public final int status;
        public RemoteFailure(String message, int status) { super(message); this.status = status; }
    }
}

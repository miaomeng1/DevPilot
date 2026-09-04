package com.devpilot.server.audit.filter;

import com.devpilot.server.audit.service.AuditLogService;
import com.devpilot.server.audit.service.AuditLogService.AuditRecord;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SecretHashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
@RequiredArgsConstructor
public class AuditCaptureFilter extends OncePerRequestFilter {

    private static final Pattern NUMERIC_ID = Pattern.compile("/(?:servers|containers|applications|configs|rules|routes|maintenance-windows|users|installations|api-tokens|webhooks|deliveries)/(\\d+)");
    private final AuditLogService auditService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod()) || path.startsWith("/api/agent/") || path.startsWith("/api/cicd/webhooks/")
                || path.equals("/api/auth/refresh") || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest rawRequest, HttpServletResponse rawResponse,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(rawRequest, 262_144);
        ContentCachingResponseWrapper response = new ContentCachingResponseWrapper(rawResponse);
        String action = action(request.getMethod(), request.getRequestURI());
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                capture(request, response, action);
            } finally {
                response.copyBodyToResponse();
            }
        }
    }

    private void capture(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, String action) {
        JsonNode requestBody = parse(request.getContentAsByteArray());
        JsonNode responseBody = response.getStatus() >= 400 ? parse(response.getContentAsByteArray()) : null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        DevPilotPrincipal principal = authentication != null && authentication.getPrincipal() instanceof DevPilotPrincipal value
                ? value : null;
        Long userId = principal == null ? responseUserId(response) : principal.userId();
        String username = principal == null ? text(requestBody, "username") : principal.getUsername();
        if (username == null) {
            username = responseUsername(response);
        }
        String path = request.getRequestURI();
        String resourceType = resourceType(path);
        String resourceId = resourceId(path);
        if (resourceId == null && response.getStatus() < 400) {
            resourceId = responseResourceId(response, resourceType);
        }
        Long serverId = serverId(resourceType, resourceId, requestBody);
        String resourceName = text(requestBody, "name");
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = forwardedFor == null || forwardedFor.isBlank()
                ? request.getRemoteAddr() : forwardedFor.split(",", 2)[0].trim();
        String error = responseBody == null ? null : text(responseBody, "message");
        auditService.record(new AuditRecord(userId, username, action, resourceType, resourceId, resourceName,
                serverId, ip, sanitizedParameters(path, request.getQueryString(), requestBody),
                response.getStatus() < 400, error == null && response.getStatus() >= 400
                ? "HTTP " + response.getStatus() : error));
    }

    private String sanitizedParameters(String path, String query, JsonNode body) {
        ObjectNode root = objectMapper.createObjectNode();
        if (query != null && !query.isBlank()) {
            root.put("query", truncate(query, 1000));
        }
        if (body != null) {
            root.set("body", sanitize(body.deepCopy(), path, null));
        }
        return root.isEmpty() ? null : truncate(root.toString(), 4000);
    }

    private JsonNode sanitize(JsonNode node, String path, String fieldName) {
        if (fieldName != null && isSecret(fieldName, path)) {
            return objectMapper.getNodeFactory().textNode("[REDACTED]");
        }
        if (fieldName != null && "content".equalsIgnoreCase(fieldName) && path.startsWith("/api/nginx/")) {
            String value = node.asText("");
            return objectMapper.getNodeFactory().textNode("[CONTENT " + value.getBytes(StandardCharsets.UTF_8).length
                    + " bytes SHA-256 " + SecretHashing.sha256(value) + "]");
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                object.set(entry.getKey(), sanitize(entry.getValue(), path, entry.getKey()));
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, sanitize(array.get(index), path, fieldName));
            }
        }
        return node;
    }

    private static boolean isSecret(String field, String path) {
        String lower = field.toLowerCase();
        if (path.matches("/api/cicd/applications/\\d+/environment(?:/sync)?") && lower.equals("value")) {
            return true;
        }
        return lower.contains("password") || (lower.contains("token") && !lower.contains("ttl")) || lower.contains("secret")
                || (path.contains("webhook") && lower.endsWith("url"))
                || (path.startsWith("/api/alerts/routes") && lower.equals("webhookurl"));
    }

    private JsonNode parse(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(content);
        } catch (IOException exception) {
            return objectMapper.getNodeFactory().textNode("[UNPARSEABLE " + content.length + " bytes]");
        }
    }

    private Long responseUserId(ContentCachingResponseWrapper response) {
        JsonNode body = parse(response.getContentAsByteArray());
        String value = body == null ? null : body.path("data").path("user").path("id").asText(null);
        return parseLong(value);
    }

    private String responseUsername(ContentCachingResponseWrapper response) {
        JsonNode body = parse(response.getContentAsByteArray());
        return body == null ? null : body.path("data").path("user").path("username").asText(null);
    }

    private String responseResourceId(ContentCachingResponseWrapper response, String resourceType) {
        JsonNode body = parse(response.getContentAsByteArray());
        if (body == null) return null;
        JsonNode data = body.path("data");
        if ("SERVER".equals(resourceType)) data = data.path("server");
        return data.path("id").asText(null);
    }

    private static Long serverId(String resourceType, String resourceId, JsonNode body) {
        if ("SERVER".equals(resourceType)) return parseLong(resourceId);
        return parseLong(text(body, "serverId"));
    }

    private static String resourceId(String path) {
        Matcher matcher = NUMERIC_ID.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String resourceType(String path) {
        if (path.startsWith("/api/servers")) return "SERVER";
        if (path.startsWith("/api/docker")) return "CONTAINER";
        if (path.startsWith("/api/applications")) return "APPLICATION";
        if (path.startsWith("/api/nginx")) return "NGINX_CONFIG";
        if (path.startsWith("/api/alerts/rules")) return "ALERT_RULE";
        if (path.startsWith("/api/alerts/routes")) return "ALERT_ROUTE";
        if (path.startsWith("/api/alerts/maintenance-windows")) return "MAINTENANCE_WINDOW";
        if (path.startsWith("/api/alerts")) return "ALERT_EVENT";
        if (path.startsWith("/api/users")) return "USER";
        if (path.startsWith("/api/settings")) return "SYSTEM_SETTING";
        if (path.startsWith("/api/api-tokens")) return "API_TOKEN";
        if (path.startsWith("/api/automation")) return "AUTOMATION_WEBHOOK";
        if (path.startsWith("/api/cicd")) return "CICD";
        if (path.startsWith("/api/service-templates")) return "SERVICE_TEMPLATE";
        if (path.startsWith("/api/auth")) return "AUTH_SESSION";
        return "API";
    }

    private static String action(String method, String path) {
        if (path.equals("/api/auth/setup")) return "INITIALIZE_SYSTEM";
        if (path.equals("/api/auth/login")) return "LOGIN";
        if (path.equals("/api/auth/logout")) return "LOGOUT";
        if (path.equals("/api/auth/password")) return "CHANGE_PASSWORD";
        if (path.matches("/api/servers/\\d+") && "DELETE".equals(method)) return "DELETE_SERVER";
        if (path.equals("/api/servers") && "POST".equals(method)) return "ADD_SERVER";
        if (path.matches(".*/containers/\\d+/start")) return "START_CONTAINER";
        if (path.matches(".*/containers/\\d+/stop")) return "STOP_CONTAINER";
        if (path.matches(".*/containers/\\d+/restart")) return "RESTART_CONTAINER";
        if (path.matches(".*/containers/\\d+/remove")) return "REMOVE_CONTAINER";
        if (path.matches(".*/containers/\\d+/logs/ticket")) return "VIEW_CONTAINER_LOGS";
        if (path.matches("/api/applications/\\d+/deployments")) return "RECORD_DEPLOYMENT";
        if (path.equals("/api/applications") && "POST".equals(method)) return "CREATE_APPLICATION";
        if (path.matches("/api/applications/\\d+") && "PUT".equals(method)) return "UPDATE_APPLICATION";
        if (path.matches("/api/applications/\\d+") && "DELETE".equals(method)) return "DELETE_APPLICATION";
        if (path.contains("/history/") && path.endsWith("/rollback")) return "ROLLBACK_NGINX";
        if (path.matches("/api/nginx/configs/\\d+") && "PUT".equals(method)) return "UPDATE_NGINX";
        if (path.equals("/api/alerts/rules") && "POST".equals(method)) return "CREATE_ALERT_RULE";
        if (path.matches("/api/alerts/rules/\\d+") && "PUT".equals(method)) return "UPDATE_ALERT_RULE";
        if (path.matches("/api/alerts/rules/\\d+") && "DELETE".equals(method)) return "DELETE_ALERT_RULE";
        if (path.equals("/api/alerts/routes") && "POST".equals(method)) return "CREATE_ALERT_ROUTE";
        if (path.matches("/api/alerts/routes/\\d+") && "PUT".equals(method)) return "UPDATE_ALERT_ROUTE";
        if (path.matches("/api/alerts/routes/\\d+") && "DELETE".equals(method)) return "DELETE_ALERT_ROUTE";
        if (path.equals("/api/alerts/maintenance-windows") && "POST".equals(method)) return "CREATE_MAINTENANCE_WINDOW";
        if (path.matches("/api/alerts/maintenance-windows/\\d+") && "DELETE".equals(method)) {
            return "DELETE_MAINTENANCE_WINDOW";
        }
        if (path.matches("/api/alerts/\\d+/acknowledge")) return "ACKNOWLEDGE_ALERT";
        if (path.equals("/api/alerts/webhook")) return "UPDATE_WEBHOOK";
        if (path.equals("/api/users") && "POST".equals(method)) return "CREATE_USER";
        if (path.matches("/api/users/\\d+/password")) return "RESET_USER_PASSWORD";
        if (path.matches("/api/users/\\d+") && "PUT".equals(method)) return "UPDATE_USER";
        if (path.matches("/api/users/\\d+") && "DELETE".equals(method)) return "DELETE_USER";
        if (path.equals("/api/settings")) return "UPDATE_SETTINGS";
        if (path.startsWith("/api/cicd/configurations/")) return "UPDATE_CICD_CONFIGURATION";
        if (path.matches("/api/cicd/applications/\\d+/environment") && "PUT".equals(method)) {
            return "UPDATE_APPLICATION_ENVIRONMENT";
        }
        if (path.matches("/api/cicd/applications/\\d+/environment/sync")) return "SYNC_APPLICATION_ENVIRONMENT";
        if (path.matches("/api/cicd/applications/\\d+/deployments/\\d+/rollback")) return "ROLLBACK_DEPLOYMENT";
        if (path.matches("/api/cicd/applications/\\d+/deployments/\\d+/promote")) return "PROMOTE_DEPLOYMENT";
        if (path.matches("/api/cicd/applications/\\d+/previews/\\d+")) return "DELETE_PREVIEW_ENVIRONMENT";
        if (path.matches("/api/service-templates/[^/]+/installations")) return "INSTALL_SERVICE_TEMPLATE";
        return method + "_API";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long parseLong(String value) {
        try { return value == null ? null : Long.valueOf(value); }
        catch (NumberFormatException exception) { return null; }
    }

    private static String truncate(String value, int maximum) {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }
}

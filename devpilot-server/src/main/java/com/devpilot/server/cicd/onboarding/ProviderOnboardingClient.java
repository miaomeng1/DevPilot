package com.devpilot.server.cicd.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import static com.devpilot.server.cicd.onboarding.OnboardingHttpClient.encode;

@Component
@RequiredArgsConstructor
public class ProviderOnboardingClient {
    private final OnboardingHttpClient http;
    public record Target(String projectId, String environmentId, String label) { }
    public record Server(String id, String name, String ip) { }
    public record Discovery(List<Target> targets, List<Server> servers) { }
    public record Application(String id, String runtimeKey) { }

    public void checkPermissions(OnboardingRequest request) {
        if (!"DOKPLOY".equals(request.deploymentProvider())) return;
        String root = OnboardingHttpClient.origin(request.providerBaseUrl(), false);
        JsonNode permissions = http.call("DOKPLOY", request.providerApiToken(), "GET", root + "/api/user.getPermissions", null);
        var required = new ArrayList<>(List.of("service.read", "service.create", "environment.read",
                "server.read", "deployment.read", "deployment.create", "envVars.write"));
        if ("__new__".equals(request.environmentId())) required.addAll(List.of("project.create", "environment.create"));
        for (String permission : required) {
            String[] pair = permission.split("\\.");
            if (!permissions.path(pair[0]).path(pair[1]).asBoolean(false)) {
                throw new IllegalArgumentException("Dokploy 缺少或无法确认权限 " + permission + "；请由管理员核对授权后重试");
            }
        }
    }

    public void checkPublishedPort(OnboardingRequest request) {
        if (!"DOKPLOY".equals(request.deploymentProvider())) return;
        String root = OnboardingHttpClient.origin(request.providerBaseUrl(), false);
        String token = request.providerApiToken();
        String selectedServer = request.providerServerId() == null ? "" : request.providerServerId();
        if (selectedServer.isBlank() && request.hostPort() == 3000) {
            throw new IllegalArgumentException("端口 3000 为 Dokploy 本机面板端口，请选择其他端口");
        }
        JsonNode projects = http.call("DOKPLOY", token, "GET", root + "/api/project.all", null);
        for (JsonNode project : projects) for (JsonNode environment : project.path("environments")) {
            for (JsonNode app : environment.path("applications")) {
                if (!selectedServer.equals(app.path("serverId").asText(""))) continue;
                JsonNode detail = verify("DOKPLOY", root, token, app.path("applicationId").asText());
                for (JsonNode port : detail.path("ports")) {
                    if (port.path("publishedPort").asInt() == request.hostPort()) {
                        throw new IllegalArgumentException("服务器端口 " + request.hostPort() + " 已被 Dokploy 应用占用，请选择其他端口");
                    }
                }
            }
        }
    }

    public Target ensureDedicatedProject(OnboardingRequest request, String code, String jobId) {
        String provider = request.deploymentProvider(), token = request.providerApiToken();
        String root = OnboardingHttpClient.origin(request.providerBaseUrl(), false);
        String marker = "DevPilot onboarding " + jobId;
        String name = "DevPilot " + code + " " + jobId.substring(0, 8);
        String path = provider.equals("DOKPLOY") ? "/api/project.all" : "/api/v1/projects";
        JsonNode project = null;
        for (JsonNode item : http.call(provider, token, "GET", root + path, null)) {
            if (marker.equals(item.path("description").asText()) && name.equals(item.path("name").asText())) project = item;
        }
        if (project == null) project = http.call(provider, token, "POST", root
                + (provider.equals("DOKPLOY") ? "/api/project.create" : "/api/v1/projects"), Map.of("name", name, "description", marker));
        // Dokploy create returns {project, environment}, while list returns project objects.
        if (provider.equals("DOKPLOY") && project.path("project").isObject()) project = project.path("project");
        String id = project.path(provider.equals("DOKPLOY") ? "projectId" : "uuid").asText();
        if (id.isBlank()) throw new IllegalArgumentException("平台未返回项目 ID；重试会核对已创建项目");
        JsonNode environments = provider.equals("DOKPLOY")
                ? http.call(provider, token, "GET", root + "/api/project.one?projectId=" + encode(id), null).path("environments")
                : http.call(provider, token, "GET", root + "/api/v1/projects/" + encode(id) + "/environments", null);
        for (JsonNode environment : environments) {
            if ("production".equalsIgnoreCase(environment.path("name").asText())) {
                return new Target(id, environment.path(provider.equals("DOKPLOY") ? "environmentId" : "uuid").asText(), name + " / production");
            }
        }
        JsonNode environment = http.call(provider, token, "POST", root + (provider.equals("DOKPLOY")
                ? "/api/environment.create" : "/api/v1/projects/" + encode(id) + "/environments"),
                provider.equals("DOKPLOY") ? Map.of("projectId", id, "name", "production") : Map.of("name", "production"));
        String environmentId = environment.path(provider.equals("DOKPLOY") ? "environmentId" : "uuid").asText();
        if (environmentId.isBlank()) throw new IllegalArgumentException("平台未返回环境 ID；重试会核对已创建环境");
        return new Target(id, environmentId, name + " / production");
    }

    public Discovery discover(String provider, String baseUrl, String token) {
        String root = OnboardingHttpClient.origin(baseUrl, false);
        List<Target> targets = new ArrayList<>();
        List<Server> servers = new ArrayList<>();
        JsonNode projects = http.call(provider, token, "GET", root
                + (provider.equals("DOKPLOY") ? "/api/project.all" : "/api/v1/projects"), null);
        if (!projects.isArray()) throw new IllegalArgumentException("部署平台项目列表格式不受支持");
        for (JsonNode project : projects) {
            String projectId = project.path(provider.equals("DOKPLOY") ? "projectId" : "uuid").asText();
            JsonNode detail = provider.equals("DOKPLOY") ? project : http.call(provider, token, "GET",
                    root + "/api/v1/projects/" + encode(projectId), null);
            for (JsonNode environment : detail.path("environments")) {
                targets.add(new Target(projectId, environment.path(provider.equals("DOKPLOY") ? "environmentId" : "uuid").asText(),
                        project.path("name").asText() + " / " + environment.path("name").asText()));
            }
        }
        if (provider.equals("DOKPLOY")) servers.add(new Server("", "Dokploy 本机", ""));
        JsonNode nodes = http.call(provider, token, "GET", root
                + (provider.equals("DOKPLOY") ? "/api/server.all" : "/api/v1/servers"), null);
        for (JsonNode server : nodes) servers.add(new Server(
                server.path(provider.equals("DOKPLOY") ? "serverId" : "uuid").asText(),
                server.path("name").asText(), server.path("ipAddress").asText(server.path("ip").asText())));
        return new Discovery(targets, servers);
    }

    public Application ensureApplication(OnboardingRequest request, String code, String jobId) {
        String provider = request.deploymentProvider(), token = request.providerApiToken();
        String root = OnboardingHttpClient.origin(request.providerBaseUrl(), false);
        String marker = "DevPilot onboarding " + jobId;
        String name = "dp-" + code.substring(0, Math.min(45, code.length())) + "-" + jobId.substring(0, 8);
        JsonNode applications;
        if (provider.equals("DOKPLOY")) {
            JsonNode project = http.call(provider, token, "GET", root + "/api/project.one?projectId=" + encode(request.projectId()), null);
            applications = null;
            for (JsonNode environment : project.path("environments")) {
                if (request.environmentId().equals(environment.path("environmentId").asText())) applications = environment.path("applications");
            }
            if (applications == null) throw new IllegalArgumentException("所选环境不属于所选项目");
        } else applications = http.call(provider, token, "GET", root + "/api/v1/applications", null);
        for (JsonNode app : applications) {
            if (!marker.equals(app.path("description").asText())) continue;
            var found = identity(provider, app);
            return provider.equals("COOLIFY") ? new Application(found.id(), "devpilot:" + jobId) : found;
        }
        if (provider.equals("DOKPLOY")) {
            var body = new LinkedHashMap<String, Object>();
            body.put("name", name); body.put("appName", name); body.put("description", marker);
            body.put("environmentId", request.environmentId()); body.put("sourceType", "docker");
            if (request.providerServerId() != null && !request.providerServerId().isBlank()) body.put("serverId", request.providerServerId());
            return identity(provider, http.call(provider, token, "POST", root + "/api/application.create", body));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name); body.put("description", marker); body.put("project_uuid", request.projectId());
        body.put("environment_uuid", request.environmentId()); body.put("server_uuid", request.providerServerId());
        body.put("docker_registry_image_name", request.imageRepository());
        body.put("docker_registry_image_tag", "pending-first-release");
        body.put("ports_exposes", Integer.toString(request.containerPort()));
        body.put("ports_mappings", request.hostPort() + ":" + request.containerPort());
        body.put("custom_labels", "com.devpilot.application=" + jobId);
        body.put("instant_deploy", false);
        var created = identity(provider, http.call(provider, token, "POST", root + "/api/v1/applications/dockerimage", body));
        return new Application(created.id(), "devpilot:" + jobId);
    }

    private Application identity(String provider, JsonNode app) {
        String id = app.path(provider.equals("DOKPLOY") ? "applicationId" : "uuid").asText();
        if (id.isBlank()) throw new IllegalArgumentException("部署平台没有返回应用 ID；请重试以核对远端资源");
        String key = provider.equals("DOKPLOY") ? "swarm:" + app.path("appName").asText() : "coolify:" + id;
        if (key.equals("swarm:")) throw new IllegalArgumentException("Dokploy 没有返回稳定的 appName");
        return new Application(id, key);
    }

    public void refreshRegistryCredentials(OnboardingRequest request, String resourceId) {
        if (!"DOKPLOY".equals(request.deploymentProvider())) {
            throw new IllegalArgumentException("该平台不支持逐应用更新镜像拉取凭据");
        }
        if (blankToNull(request.registryUsername()) == null || blankToNull(request.registryPassword()) == null) {
            throw new IllegalArgumentException("更新拉取凭据需要已有 Registry 用户名和新的密码");
        }
        String root = OnboardingHttpClient.origin(request.providerBaseUrl(), false);
        JsonNode app = verify("DOKPLOY", root, request.providerApiToken(), resourceId);
        String image = app.path("dockerImage").asText("");
        if (image.isBlank()) throw new IllegalArgumentException("无法确认部署平台当前镜像，未修改拉取凭据");
        var body = new LinkedHashMap<String, Object>();
        body.put("applicationId", resourceId); body.put("dockerImage", image);
        body.put("username", blankToNull(request.registryUsername()));
        body.put("password", blankToNull(request.registryPassword()));
        body.put("registryUrl", "https://" + request.imageRepository().split("/")[0]);
        http.call("DOKPLOY", request.providerApiToken(), "POST", root + "/api/application.saveDockerProvider", body);
    }

    public void configure(OnboardingRequest request, String resourceId) {
        String provider = request.deploymentProvider(), token = request.providerApiToken();
        String root = OnboardingHttpClient.origin(request.providerBaseUrl(), false);
        Map<String, String> env = request.environmentValues() == null ? Map.of() : request.environmentValues();
        if (provider.equals("DOKPLOY")) {
            var registry = new LinkedHashMap<String, Object>();
            registry.put("applicationId", resourceId); registry.put("dockerImage", request.imageRepository() + ":pending-first-release");
            registry.put("username", blankToNull(request.registryUsername())); registry.put("password", blankToNull(request.registryPassword()));
            registry.put("registryUrl", "https://" + request.imageRepository().split("/")[0]);
            http.call(provider, token, "POST", root + "/api/application.saveDockerProvider", registry);
            JsonNode app = verify(provider, root, token, resourceId);
            boolean portExists = false;
            for (JsonNode port : app.path("ports")) {
                if (port.path("publishedPort").asInt() == request.hostPort()
                        && port.path("targetPort").asInt() == request.containerPort()) portExists = true;
            }
            if (!portExists) http.call(provider, token, "POST", root + "/api/port.create",
                    Map.of("applicationId", resourceId, "publishedPort", request.hostPort(), "targetPort", request.containerPort(),
                            "protocol", "tcp", "publishMode", "ingress"));
            if (!env.isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (var entry : env.entrySet()) {
                    if (entry.getValue().contains("\n") || entry.getValue().contains("\r")) throw new IllegalArgumentException("Dokploy 接入变量暂不接受多行值");
                    text.append(entry.getKey()).append("='").append(entry.getValue().replace("'", "\\'")).append("'\n");
                }
                // Only a new, job-owned app is passed here; never replace an existing user's environment.
                http.call(provider, token, "POST", root + "/api/application.saveEnvironment",
                        Map.of("applicationId", resourceId, "env", text.toString(), "buildArgs", "", "buildSecrets", "", "createEnvFile", false));
            }
        } else {
            if (blankToNull(request.registryPassword()) != null) {
                throw new IllegalArgumentException("Coolify 私有镜像需先在目标服务器配置 Registry 登录；API 不提供安全的逐应用拉取凭据配置");
            }
            JsonNode existing = http.call(provider, token, "GET", root + "/api/v1/applications/" + encode(resourceId) + "/envs", null);
            for (var entry : env.entrySet()) {
                boolean found = false;
                for (JsonNode item : existing) if (entry.getKey().equals(item.path("key").asText())) {
                    http.call(provider, token, "PATCH", root + "/api/v1/applications/" + encode(resourceId) + "/envs",
                            Map.of("uuid", item.path("uuid").asText(), "key", entry.getKey(), "value", entry.getValue(), "is_buildtime", false));
                    found = true;
                }
                if (!found) http.call(provider, token, "POST", root + "/api/v1/applications/" + encode(resourceId) + "/envs",
                        Map.of("key", entry.getKey(), "value", entry.getValue(), "is_buildtime", false));
            }
        }
        verify(provider, root, token, resourceId);
    }

    public JsonNode verify(String provider, String baseUrl, String token, String resourceId) {
        String root = OnboardingHttpClient.origin(baseUrl, false);
        JsonNode app = http.call(provider, token, "GET", root + (provider.equals("DOKPLOY")
                ? "/api/application.one?applicationId=" + encode(resourceId)
                : "/api/v1/applications/" + encode(resourceId)), null);
        String returnedId = app.path(provider.equals("DOKPLOY") ? "applicationId" : "uuid").asText();
        if (!resourceId.equals(returnedId)) throw new IllegalArgumentException("部署平台返回的应用与配置 ID 不一致");
        return app;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}

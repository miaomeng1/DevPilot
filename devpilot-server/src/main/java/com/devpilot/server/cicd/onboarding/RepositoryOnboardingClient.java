package com.devpilot.server.cicd.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static com.devpilot.server.cicd.onboarding.OnboardingHttpClient.encode;

@Component
@RequiredArgsConstructor
public class RepositoryOnboardingClient {
    private final OnboardingHttpClient http;

    public record Repository(String api, String path, String branch, String imageRepository, String dockerfile,
                             String runtime, String webUrl) { }

    public Repository inspect(String provider, String url, String token) {
        String origin = OnboardingHttpClient.origin(url, true);
        String path = URI.create(url).getPath().replaceAll("^/|/+$", "").replaceAll("\\.git$", "");
        if (!path.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")) {
            throw new IllegalArgumentException("仓库地址必须包含 owner/project 路径");
        }
        boolean github = "GITHUB".equals(provider);
        if (github && (!origin.equals("https://github.com") || path.split("/").length != 2)) {
            throw new IllegalArgumentException("GitHub 自动接入当前使用 github.com 仓库地址");
        }
        String api = github ? "https://api.github.com/repos/" + path : origin + "/api/v4/projects/" + encode(path);
        JsonNode repo = http.call(provider, token, "GET", api, null);
        if (repo.path("archived").asBoolean() || repo.path("empty_repo").asBoolean()) {
            throw new IllegalArgumentException("归档或空仓库不能接入，请先提交业务代码");
        }
        String branch = repo.path("default_branch").asText("");
        if (branch.isBlank()) throw new IllegalArgumentException("仓库没有默认分支");
        String image = github ? "ghcr.io/" + path.toLowerCase(Locale.ROOT)
                : repo.path("container_registry_image_prefix").asText(
                    origin.equals("https://gitlab.com") ? "registry.gitlab.com/" + path : "");
        Repository result = new Repository(api, path, branch, image, "", "DOCKER", url);
        String docker = file(provider, token, result, "Dockerfile", branch);
        if (docker == null) throw new IllegalArgumentException("仓库根目录缺少 Dockerfile；请先提供可运行的容器构建定义，系统不会猜测启动命令");
        String runtime = docker.matches("(?is).*\\bAS\\s+test\\b.*") ? "DOCKER"
                : file(provider, token, result, "package.json", branch) != null ? "NODE"
                : file(provider, token, result, "pom.xml", branch) != null ? "JAVA"
                : file(provider, token, result, "go.mod", branch) != null ? "GO" : "DOCKER";
        if (runtime.equals("DOCKER") && !docker.matches("(?is).*\\bAS\\s+test\\b.*")) {
            throw new IllegalArgumentException("未识别测试入口；Dockerfile 需要 test stage，或使用 Node/Maven/Go 项目");
        }
        return new Repository(api, path, branch, image, docker, runtime, url);
    }

    public String file(String provider, String token, Repository repo, String path, String branch) {
        String endpoint = repo.api() + (provider.equals("GITHUB") ? "/contents/" + path
                : "/repository/files/" + encode(path)) + "?ref=" + encode(branch);
        JsonNode file = http.optional(provider, token, endpoint);
        if (file == null) return null;
        if (!file.path("encoding").asText().equals("base64") || file.path("size").asLong() > 500000) {
            throw new IllegalArgumentException("接入文件必须为小于 500 KB 的文本文件");
        }
        return new String(Base64.getMimeDecoder().decode(file.path("content").asText()), StandardCharsets.UTF_8);
    }

    public void configureSecrets(OnboardingRequest request, Repository repo, String applicationCode, String callbackSecret) {
        String provider = request.repositoryProvider(), token = request.repositoryToken();
        String environment = "production-" + applicationCode;
        String callback = request.publicBaseUrl().replaceAll("/+$", "") + "/api/cicd/webhooks/" + applicationCode;
        if (provider.equals("GITHUB")) {
            // Per-application environment avoids overwriting secrets of another project in a monorepo.
            String root = repo.api() + "/environments/" + encode(environment);
            if (http.optional(provider, token, root) == null) {
                http.call(provider, token, "PUT", root, Map.of());
            }
            JsonNode key = http.call(provider, token, "GET", root + "/secrets/public-key", null);
            for (var secret : Map.of("DEVPILOT_CICD_CALLBACK_URL", callback,
                    "DEVPILOT_CICD_CALLBACK_SECRET", callbackSecret).entrySet()) {
                http.call(provider, token, "PUT", root + "/secrets/" + secret.getKey(),
                        Map.of("key_id", key.path("key_id").asText(), "encrypted_value",
                                GithubSecretBox.seal(key.path("key").asText(), secret.getValue())));
                http.call(provider, token, "GET", root + "/secrets/" + secret.getKey(), null);
            }
        } else {
            JsonNode branch = http.call(provider, token, "GET", repo.api() + "/repository/branches/" + encode(request.branch()), null);
            if (!branch.path("protected").asBoolean()) {
                throw new IllegalArgumentException("GitLab 发布分支尚未受保护；请先保护该分支，避免自动接入改变已有协作权限");
            }
            for (var secret : Map.of("DEVPILOT_CICD_CALLBACK_URL", callback,
                    "DEVPILOT_CICD_CALLBACK_SECRET", callbackSecret).entrySet()) {
                String endpoint = repo.api() + "/variables/" + secret.getKey() + "?filter[environment_scope]=" + encode(environment);
                // Encode brackets for java.net.URI while preserving GitLab's filter name.
                endpoint = endpoint.replace("[", "%5B").replace("]", "%5D");
                boolean exists = http.optional(provider, token, endpoint) != null;
                var body = Map.of("key", secret.getKey(), "value", secret.getValue(), "environment_scope", environment,
                        "protected", true, "masked", true, "raw", true);
                http.call(provider, token, exists ? "PUT" : "POST", exists ? endpoint : repo.api() + "/variables", body);
            }
        }
    }

    public String proposeWorkflow(OnboardingRequest request, Repository repo, String code, String jobId) {
        String branch = "codex/devpilot-" + code + "-" + jobId;
        String token = request.repositoryToken(), provider = request.repositoryProvider();
        String content = request.workflowContent();
        if (content == null || content.isBlank()) throw new IllegalArgumentException("缺少已确认的流水线预览");
        // The generated template keeps the explicit human release gate and a dedicated secret scope.
        content = content.replace("environment: production\n", "environment: production-" + code + "\n")
                .replace("name: production\n", "name: production-" + code + "\n");
        if (provider.equals("GITHUB")) {
            if (!content.contains("workflow_dispatch") || !content.contains("github.event_name == 'workflow_dispatch'")) {
                throw new IllegalArgumentException("生产发布必须保留 workflow_dispatch 人工确认");
            }
            if (http.optional(provider, token, repo.api() + "/git/ref/heads/" + encode(branch)) == null) {
                JsonNode ref = http.call(provider, token, "GET", repo.api() + "/git/ref/heads/" + encode(request.branch()), null);
                http.call(provider, token, "POST", repo.api() + "/git/refs", Map.of("ref", "refs/heads/" + branch,
                        "sha", ref.path("object").path("sha").asText()));
            }
            writeGithub(request, repo, ".github/workflows/devpilot-" + code + ".yml", branch, content);
            String head = repo.path().split("/")[0] + ":" + branch;
            JsonNode prs = http.call(provider, token, "GET", repo.api() + "/pulls?state=all&head=" + encode(head), null);
            if (prs.isArray() && !prs.isEmpty()) return prs.get(0).path("html_url").asText();
            return http.call(provider, token, "POST", repo.api() + "/pulls", Map.of("title", "接入 DevPilot · " + code,
                    "head", branch, "base", request.branch(), "body", "自动生成测试、扫描、镜像构建和签名回调。生产发布仍需人工确认。请审阅后合并；不会自动部署。"))
                    .path("html_url").asText();
        }
        if (!content.contains("when: manual")) throw new IllegalArgumentException("GitLab 必须保留 manual 生产发布");
        if (http.optional(provider, token, repo.api() + "/repository/branches/" + encode(branch)) == null) {
            http.call(provider, token, "POST", repo.api() + "/repository/branches", Map.of("branch", branch, "ref", request.branch()));
        }
        String path = ".devpilot/" + code + ".yml";
        writeGitlab(request, repo, path, branch, content);
        String original = file(provider, token, repo, ".gitlab-ci.yml", branch);
        String merged = withGitlabInclude(original, path);
        writeGitlab(request, repo, ".gitlab-ci.yml", branch, merged);
        JsonNode mrs = http.call(provider, token, "GET", repo.api() + "/merge_requests?state=all&source_branch=" + encode(branch), null);
        if (mrs.isArray() && !mrs.isEmpty()) return mrs.get(0).path("web_url").asText();
        return http.call(provider, token, "POST", repo.api() + "/merge_requests", Map.of("source_branch", branch,
                "target_branch", request.branch(), "title", "接入 DevPilot · " + code,
                "description", "生成交付流水线；保留人工生产发布，请审阅后合并。"))
                .path("web_url").asText();
    }

    private void writeGithub(OnboardingRequest request, Repository repo, String path, String branch, String content) {
        String existing = file("GITHUB", request.repositoryToken(), repo, path, branch);
        if (content.equals(existing)) return;
        if (existing != null) throw new IllegalArgumentException("仓库已有同名流水线，未覆盖；请更换应用编码或先人工合并现有配置");
        http.call("GITHUB", request.repositoryToken(), "PUT", repo.api() + "/contents/" + path,
                Map.of("message", "ci: onboard DevPilot", "branch", branch,
                        "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));
    }

    private void writeGitlab(OnboardingRequest request, Repository repo, String path, String branch, String content) {
        String existing = file("GITLAB", request.repositoryToken(), repo, path, branch);
        if (content.equals(existing)) return;
        if (existing != null && !path.equals(".gitlab-ci.yml")) throw new IllegalArgumentException("同名流水线已存在，未覆盖");
        var body = new LinkedHashMap<String, Object>();
        body.put("branch", branch); body.put("content", content); body.put("commit_message", "ci: onboard DevPilot");
        if (existing != null) {
            var old = http.call("GITLAB", request.repositoryToken(), "GET", repo.api() + "/repository/files/"
                    + encode(path) + "?ref=" + encode(branch), null);
            body.put("last_commit_id", old.path("last_commit_id").asText());
        }
        http.call("GITLAB", request.repositoryToken(), existing == null ? "POST" : "PUT",
                repo.api() + "/repository/files/" + encode(path), body);
    }

    @SuppressWarnings("unchecked")
    static String withGitlabInclude(String original, String path) {
        if (original == null || original.isBlank()) return "include:\n  - local: '" + path + "'\n";
        // A child pipeline isolates our stages, job names and variables from existing CI.
        // Compose nodes without constructing objects: GitLab's !reference tags remain verbatim.
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        var node = yaml.compose(new java.io.StringReader(original));
        if (!(node instanceof org.yaml.snakeyaml.nodes.MappingNode root)) throw new IllegalArgumentException("现有 GitLab CI 不是有效配置映射");
        String key = "devpilot_" + path.substring(path.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_]", "_");
        String addition = "\n# Managed DevPilot child pipeline: " + path + "\n" + key + ":\n  stage: .post\n"
                + "  trigger:\n    include:\n      - local: '" + path + "'\n    strategy: depend\n"
                + "  rules:\n    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'\n";
        if (original.endsWith(addition)) return original;
        for (var entry : root.getValue()) {
            if (entry.getKeyNode() instanceof org.yaml.snakeyaml.nodes.ScalarNode scalar && key.equals(scalar.getValue())) {
                throw new IllegalArgumentException("现有 GitLab CI 已有同名 DevPilot 触发任务，未覆盖");
            }
        }
        return original + addition;
    }
}

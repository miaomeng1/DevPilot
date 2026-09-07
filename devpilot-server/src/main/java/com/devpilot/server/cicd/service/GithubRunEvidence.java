package com.devpilot.server.cicd.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

/** Identity-bound observation; never grants build success or release approval. */
public final class GithubRunEvidence {
    private static final Pattern EXTERNAL = Pattern.compile("build:github-([1-9][0-9]{0,18})-([1-9][0-9]{0,8})");
    private static final Pattern REPOSITORY = Pattern.compile("/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?");
    private static final Set<String> ACTIVE = Set.of("queued", "in_progress", "requested", "waiting", "pending");

    private GithubRunEvidence() {}

    public record Identity(String repository, String runId, String attempt, String commit, String branch) {
        public String apiPath() {
            return "/repos/" + repository + "/actions/runs/" + runId + "/attempts/" + attempt;
        }
    }

    public enum State { ACTIVE, FAILED, CANCELLED, SUCCESS_AWAITING_BUILD_EVIDENCE, UNKNOWN }

    public static Identity identity(String repositoryUrl, String externalRunId, String commit, String branch) {
        try {
            URI uri = URI.create(repositoryUrl);
            var repository = REPOSITORY.matcher(uri.getRawPath());
            var run = EXTERNAL.matcher(externalRunId);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !repository.matches() || !run.matches()
                    || commit == null || !commit.matches("[a-fA-F0-9]{40}") || branch == null || branch.isBlank()) {
                throw new IllegalArgumentException("Unsupported GitHub build identity");
            }
            String owner = repository.group(1), repo = repository.group(2);
            if (owner.equals(".") || owner.equals("..") || repo.equals(".") || repo.equals("..")) {
                throw new IllegalArgumentException("Invalid repository path");
            }
            return new Identity(owner + "/" + repo, run.group(1), run.group(2), commit, branch);
        } catch (RuntimeException exception) {
            // Never reflect input URLs or credential-bearing strings into errors.
            throw new IllegalArgumentException("Unsupported GitHub build identity");
        }
    }

    public static State evaluate(Identity expected, JsonNode response) {
        if (response == null || !response.isObject()
                || !expected.runId().equals(response.path("id").asText())
                || !expected.attempt().equals(response.path("run_attempt").asText())
                || !expected.commit().equalsIgnoreCase(response.path("head_sha").asText())
                || !expected.branch().equals(response.path("head_branch").asText())
                || !expected.repository().equalsIgnoreCase(response.path("repository").path("full_name").asText())
                || !expected.repository().equalsIgnoreCase(response.path("head_repository").path("full_name").asText())
                || !"push".equals(response.path("event").asText())) {
            throw new IllegalArgumentException("GitHub response does not match this build attempt");
        }
        String status = response.path("status").asText();
        JsonNode conclusion = response.path("conclusion");
        if (ACTIVE.contains(status)) {
            return conclusion.isMissingNode() || conclusion.isNull() ? State.ACTIVE : State.UNKNOWN;
        }
        if (!"completed".equals(status)) return State.UNKNOWN;
        return switch (conclusion.asText()) {
            case "failure", "timed_out", "startup_failure" -> State.FAILED;
            case "cancelled" -> State.CANCELLED;
            // Workflow success alone does not establish quality jobs or digest.
            case "success" -> State.SUCCESS_AWAITING_BUILD_EVIDENCE;
            default -> State.UNKNOWN;
        };
    }
}

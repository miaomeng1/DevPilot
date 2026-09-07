package com.devpilot.server.cicd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GithubRunEvidenceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final GithubRunEvidence.Identity identity = GithubRunEvidence.identity(
            "https://github.com/acme/demo.git", "build:github-42-2", "a".repeat(40), "main");

    private ObjectNode response() {
        ObjectNode result = json.createObjectNode();
        result.put("id", 42).put("run_attempt", 2).put("head_sha", "a".repeat(40))
                .put("head_branch", "main").put("event", "push").put("status", "completed").put("conclusion", "success");
        result.putObject("repository").put("full_name", "acme/demo");
        result.putObject("head_repository").put("full_name", "acme/demo");
        return result;
    }

    @Test void derivesAttemptSpecificPathWithoutTrustingCallbackLinks() {
        assertEquals("/repos/acme/demo/actions/runs/42/attempts/2", identity.apiPath());
        for (String url : new String[]{"http://github.com/acme/demo", "https://evil.example/acme/demo",
                "https://token@github.com/acme/demo", "https://github.com:443/acme/demo",
                "https://github.com/acme/demo?token=secret", "https://github.com/acme/demo#x",
                "https://github.com/acme/%2e%2e", "https://github.com/../demo"}) {
            assertThrows(IllegalArgumentException.class, () -> GithubRunEvidence.identity(url, "build:github-42-2", "a".repeat(40), "main"));
        }
        for (String run : new String[]{"github-42-2", "build:github-42-0", "build:github-42-2/evil", "build:github-1-2?x"}) {
            assertThrows(IllegalArgumentException.class, () -> GithubRunEvidence.identity("https://github.com/acme/demo", run, "a".repeat(40), "main"));
        }
    }

    @Test void bindsRepositoryCommitBranchAttemptAndPushEvent() {
        for (String field : new String[]{"id", "run_attempt", "head_sha", "head_branch", "event"}) {
            ObjectNode value = response(); value.put(field, "wrong");
            assertThrows(IllegalArgumentException.class, () -> GithubRunEvidence.evaluate(identity, value), field);
            value.remove(field);
            assertThrows(IllegalArgumentException.class, () -> GithubRunEvidence.evaluate(identity, value), "missing " + field);
        }
        for (String field : new String[]{"repository", "head_repository"}) {
            ObjectNode value = response(); value.putObject(field).put("full_name", "fork/demo");
            assertThrows(IllegalArgumentException.class, () -> GithubRunEvidence.evaluate(identity, value));
        }
    }

    @Test void successCannotBecomeApprovedBuildAndUnknownConclusionsFailClosed() {
        assertEquals(GithubRunEvidence.State.SUCCESS_AWAITING_BUILD_EVIDENCE, GithubRunEvidence.evaluate(identity, response()));
        for (String conclusion : new String[]{"failure", "timed_out", "startup_failure"}) {
            assertEquals(GithubRunEvidence.State.FAILED, GithubRunEvidence.evaluate(identity, response().put("conclusion", conclusion)));
        }
        assertEquals(GithubRunEvidence.State.CANCELLED, GithubRunEvidence.evaluate(identity, response().put("conclusion", "cancelled")));
        for (String conclusion : new String[]{"neutral", "skipped", "stale", "action_required", "new_status", ""}) {
            assertEquals(GithubRunEvidence.State.UNKNOWN, GithubRunEvidence.evaluate(identity, response().put("conclusion", conclusion)));
        }
        assertEquals(GithubRunEvidence.State.ACTIVE, GithubRunEvidence.evaluate(identity, response().put("status", "in_progress").putNull("conclusion")));
        assertEquals(GithubRunEvidence.State.UNKNOWN, GithubRunEvidence.evaluate(identity, response().put("status", "in_progress")));
    }
}

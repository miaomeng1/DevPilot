package com.devpilot.server.cicd.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeImageTests {
    @Test void swarmTagAndDigestReferenceMatchesTheRequestedTag() {
        assertTrue(CicdDeploymentService.runtimeImageMatches("ghcr.io/acme/api:sha-1234567",
                "ghcr.io/acme/api:sha-1234567@sha256:" + "a".repeat(64)));
        assertFalse(CicdDeploymentService.runtimeImageMatches("ghcr.io/acme/api:sha-1234567", "ghcr.io/acme/api:sha-7654321"));
    }
    @Test void digestMustMatchRepositoryAndContent() {
        String digest = "@sha256:" + "a".repeat(64);
        assertTrue(CicdDeploymentService.runtimeImageMatches("registry.example:5000/api" + digest, "registry.example:5000/api:tag" + digest));
        assertFalse(CicdDeploymentService.runtimeImageMatches("registry.example:5000/api" + digest, "registry.example:5000/other:tag" + digest));
        assertFalse(CicdDeploymentService.runtimeImageMatches("registry.example:5000/api" + digest, "registry.example:5000/api@sha256:" + "b".repeat(64)));
    }
}

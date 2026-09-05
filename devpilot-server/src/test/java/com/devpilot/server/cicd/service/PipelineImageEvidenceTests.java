package com.devpilot.server.cicd.service;

import com.devpilot.server.cicd.dto.PipelineCallbackRequest;
import com.devpilot.server.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelineImageEvidenceTests {
    private PipelineCallbackRequest callback(String image, String digest) {
        return new PipelineCallbackRequest("run", "SUCCEEDED", "PASSED", "PASSED", "a".repeat(40),
                "main", image, digest, null, null);
    }
    @Test void matchingDigestAcceptedButContradictoryEvidenceRejected() {
        String digest = "sha256:" + "a".repeat(64);
        assertDoesNotThrow(() -> CicdService.validateSuccessfulGate(callback("ghcr.io/acme/app@" + digest, digest)));
        assertDoesNotThrow(() -> CicdService.validateSuccessfulGate(callback("ghcr.io/acme/app@" + digest, null)));
        assertThrows(BusinessException.class, () -> CicdService.validateSuccessfulGate(callback("ghcr.io/acme/app@" + digest, "sha256:" + "b".repeat(64))));
        assertThrows(BusinessException.class, () -> CicdService.validateSuccessfulGate(callback("ghcr.io/acme/app:sha-aaaaaaa", digest)));
        assertThrows(BusinessException.class, () -> CicdService.validateSuccessfulGate(callback("ghcr.io/acme/app@" + digest, "invalid")));
    }
}

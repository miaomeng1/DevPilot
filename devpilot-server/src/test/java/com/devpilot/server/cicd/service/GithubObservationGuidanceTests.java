package com.devpilot.server.cicd.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GithubObservationGuidanceTests {
    @Test void errorsExplainActionsWithoutClaimingBuildFailure() {
        assertTrue(GithubObservationGuidance.describe("CREDENTIAL_OR_PERMISSION").contains("Actions 只读权限"));
        assertTrue(GithubObservationGuidance.describe("CREDENTIAL_REQUIRED").contains("不代表构建失败"));
        assertTrue(GithubObservationGuidance.describe("RATE_LIMITED").contains("避免连续手动重试"));
        assertTrue(GithubObservationGuidance.describe("RUN_NOT_ACCESSIBLE").contains("访问权限"));
        for (String code : new String[]{"NETWORK_OR_TIMEOUT", "REMOTE_UNAVAILABLE", "INTERRUPTED"}) {
            assertTrue(GithubObservationGuidance.describe(code).contains("不能据此判断构建失败或应用离线"));
        }
    }

    @Test void successKeepsEvidenceAndManualApprovalRequirements() {
        String success = GithubObservationGuidance.describe("SUCCESS_AWAITING_BUILD_EVIDENCE");
        for (String required : new String[]{"测试", "安全扫描", "digest", "回调", "人工确认前不能发布"}) {
            assertTrue(success.contains(required), required);
        }
        assertTrue(GithubObservationGuidance.describe("FAILED").contains("没有触发部署"));
        assertTrue(GithubObservationGuidance.describe("CANCELLED").contains("不会重跑"));
        assertTrue(GithubObservationGuidance.describe("ACTIVE").contains("不是应用在线证明"));
    }

    @Test void unknownOrUntrustedCodesAreNeverReflected() {
        String unknown = GithubObservationGuidance.describe(null);
        assertTrue(unknown.contains("未知"));
        assertEquals(unknown, GithubObservationGuidance.describe("UNKNOWN"));
        assertEquals(unknown, GithubObservationGuidance.describe("token=secret_from_remote"));
        assertTrue(GithubObservationGuidance.describe("INVALID_OR_MISMATCHED_EVIDENCE").contains("不能作为发布依据"));
    }
}

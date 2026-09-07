package com.devpilot.server.cicd.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OnboardingHttpGuidanceTests {
    @Test void rateLimitSuggestsWaitingInsteadOfChangingCredentials() {
        String message = OnboardingHttpClient.failureGuidance(429);
        assertTrue(message.contains("等待限流窗口恢复"));
        assertTrue(message.contains("不要反复提交或重新创建资源"));
        assertFalse(message.contains("更新凭据"));
    }

    @Test void authenticationAndForbiddenAreNotConfused() {
        assertTrue(OnboardingHttpClient.failureGuidance(401).contains("更新凭据"));
        String forbidden = OnboardingHttpClient.failureGuidance(403);
        assertTrue(forbidden.contains("访问权"));
        assertTrue(forbidden.contains("限流"));
        assertFalse(forbidden.contains("凭据无效"));
    }

    @Test void unavailableUpstreamDoesNotClaimRemoteWriteFailed() {
        for (int status : new int[]{500, 502, 503, 504}) {
            String message = OnboardingHttpClient.failureGuidance(status);
            assertTrue(message.contains("远端可能已执行"));
            assertTrue(message.contains("核对现有资源"));
        }
        assertTrue(OnboardingHttpClient.failureGuidance(404).contains("不可见"));
        assertTrue(OnboardingHttpClient.failureGuidance(422).contains("请求配置"));
    }
}

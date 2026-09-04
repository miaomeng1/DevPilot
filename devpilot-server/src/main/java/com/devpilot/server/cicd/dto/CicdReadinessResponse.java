package com.devpilot.server.cicd.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CicdReadinessResponse(
        Long applicationId,
        boolean ready,
        int score,
        int blockerCount,
        int warningCount,
        String summary,
        LocalDateTime checkedAt,
        List<CicdReadinessCheckResponse> checks) {
}

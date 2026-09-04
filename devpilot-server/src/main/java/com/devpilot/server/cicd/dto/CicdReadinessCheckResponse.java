package com.devpilot.server.cicd.dto;

public record CicdReadinessCheckResponse(
        String code,
        String status,
        String title,
        String detail,
        String action) {
}

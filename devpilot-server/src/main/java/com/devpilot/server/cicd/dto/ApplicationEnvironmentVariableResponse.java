package com.devpilot.server.cicd.dto;

public record ApplicationEnvironmentVariableResponse(
        String key,
        String value,
        boolean secret,
        boolean configured,
        String description) {
}

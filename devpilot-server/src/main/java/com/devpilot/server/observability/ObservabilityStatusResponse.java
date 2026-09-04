package com.devpilot.server.observability;

public record ObservabilityStatusResponse(
        boolean prometheusEnabled,
        String prometheusPath,
        String prometheusAuthentication,
        boolean otlpEnabled,
        String otlpProtocol,
        long snapshotIntervalSeconds) {
}

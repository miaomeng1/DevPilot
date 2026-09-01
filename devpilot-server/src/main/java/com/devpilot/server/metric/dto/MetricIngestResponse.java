package com.devpilot.server.metric.dto;

import java.time.Instant;

public record MetricIngestResponse(Long serverId, Instant acceptedAt) {
}

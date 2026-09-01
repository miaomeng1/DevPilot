package com.devpilot.server.metric.dto;

import java.util.List;

public record MetricHistoryResponse(
        String serverId,
        String range,
        int resolutionSeconds,
        MetricPointResponse current,
        List<MetricPointResponse> points) {
}

package com.devpilot.server.monitor.dto;

import com.devpilot.server.metric.dto.MetricPointResponse;
import java.util.List;

public record MonitorResponse(
        MonitorSummaryResponse summary,
        String range,
        List<MetricPointResponse> trend,
        List<MonitorServerResponse> servers) {
}

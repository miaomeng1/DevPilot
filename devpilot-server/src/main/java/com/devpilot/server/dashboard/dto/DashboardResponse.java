package com.devpilot.server.dashboard.dto;

import com.devpilot.server.alert.dto.AlertEventResponse;
import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.application.dto.ApplicationDeploymentResponse;
import com.devpilot.server.application.dto.ApplicationResponse;
import com.devpilot.server.monitor.dto.MonitorServerResponse;
import java.util.List;

public record DashboardResponse(
        DashboardSummaryResponse summary,
        String range,
        List<MetricPointResponse> trend,
        List<MonitorServerResponse> serverResources,
        List<ApplicationResponse> serviceStatuses,
        List<ApplicationDeploymentResponse> recentDeployments,
        List<AlertEventResponse> alerts) {
}

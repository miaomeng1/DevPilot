package com.devpilot.server.dashboard.service;

import com.devpilot.server.alert.service.AlertEventService;
import com.devpilot.server.dashboard.dto.DashboardResponse;
import com.devpilot.server.dashboard.dto.DashboardSummaryResponse;
import com.devpilot.server.application.service.ApplicationService;
import com.devpilot.server.metric.service.MetricRange;
import com.devpilot.server.metric.service.MetricService;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.monitor.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ServerNodeMapper serverNodeMapper;
    private final MetricService metricService;
    private final DockerContainerSnapshotMapper dockerContainerMapper;
    private final ApplicationService applicationService;
    private final AlertEventService alertEventService;
    private final MonitorService monitorService;

    public DashboardResponse get(String rangeValue) {
        MetricRange range = MetricRange.parse(rangeValue);
        DashboardSummaryResponse summary = new DashboardSummaryResponse(
                serverNodeMapper.countAllActive(), serverNodeMapper.countOnline(),
                dockerContainerMapper.countAllActive(), dockerContainerMapper.countRunning(),
                applicationService.count(), applicationService.countUnhealthy(), alertEventService.summary().active(),
                applicationService.countDeploymentsToday());
        return new DashboardResponse(summary, range.value(), metricService.globalTrend(range),
                monitorService.servers().stream().limit(6).toList(),
                applicationService.serviceStatuses(), applicationService.recentDeployments(), alertEventService.current(6));
    }
}

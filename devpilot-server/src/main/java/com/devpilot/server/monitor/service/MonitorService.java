package com.devpilot.server.monitor.service;

import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.metric.service.MetricRange;
import com.devpilot.server.metric.service.MetricService;
import com.devpilot.server.monitor.dto.MonitorResponse;
import com.devpilot.server.monitor.dto.MonitorServerResponse;
import com.devpilot.server.monitor.dto.MonitorSummaryResponse;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MonitorService {

    private final ServerNodeMapper serverNodeMapper;
    private final MetricService metricService;

    public MonitorResponse get(String rangeValue) {
        MetricRange range = MetricRange.parse(rangeValue);
        List<MonitorServerResponse> servers = servers();
        List<MetricPointResponse> reporting = servers.stream()
                .map(MonitorServerResponse::current).filter(value -> value != null).toList();
        MonitorSummaryResponse summary = new MonitorSummaryResponse(
                servers.size(), servers.stream().filter(server -> "ONLINE".equals(server.status())).count(),
                reporting.size(), average(reporting, MetricPointResponse::cpuUsage),
                average(reporting, MetricPointResponse::memoryUsage),
                average(reporting, MetricPointResponse::diskUsage),
                sum(reporting, MetricPointResponse::networkUploadRate),
                sum(reporting, MetricPointResponse::networkDownloadRate));
        return new MonitorResponse(summary, range.value(), metricService.globalTrend(range), servers);
    }

    public List<MonitorServerResponse> servers() {
        return serverNodeMapper.selectAllActive().stream()
                .map(server -> MonitorServerResponse.from(server, metricService.current(server.getId()))).toList();
    }

    private static double average(List<MetricPointResponse> points,
                                  java.util.function.ToDoubleFunction<MetricPointResponse> value) {
        return round(points.stream().mapToDouble(value).average().orElse(0));
    }

    private static double sum(List<MetricPointResponse> points,
                              java.util.function.ToDoubleFunction<MetricPointResponse> value) {
        return round(points.stream().mapToDouble(value).sum());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

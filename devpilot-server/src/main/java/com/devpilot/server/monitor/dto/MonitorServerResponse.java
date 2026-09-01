package com.devpilot.server.monitor.dto;

import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.node.entity.ServerNodeEntity;
import java.time.LocalDateTime;

public record MonitorServerResponse(
        Long id,
        String name,
        String hostname,
        String ip,
        String status,
        Integer cpuCores,
        Long memoryTotal,
        Long diskTotal,
        LocalDateTime lastHeartbeat,
        MetricPointResponse current) {

    public static MonitorServerResponse from(ServerNodeEntity server, MetricPointResponse current) {
        return new MonitorServerResponse(server.getId(), server.getName(), server.getHostname(), server.getIp(),
                server.getAgentStatus(), server.getCpuCores(), server.getMemoryTotal(), server.getDiskTotal(),
                server.getLastHeartbeat(), current);
    }
}

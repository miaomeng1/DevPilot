package com.devpilot.server.capacity.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CapacityServerResponse(
        Long serverId,
        String serverName,
        String hostname,
        String architecture,
        boolean eligible,
        boolean recommended,
        int score,
        String grade,
        Double cpuUsage,
        Double loadPerCore,
        Double memoryUsage,
        Double projectedMemoryUsage,
        Long memoryAvailableAfter,
        Double diskUsage,
        Double projectedDiskUsage,
        Long diskFreeAfter,
        int runningContainers,
        int activeAlerts,
        int criticalAlerts,
        LocalDateTime metricAt,
        List<String> blockers,
        List<String> observations) {
}

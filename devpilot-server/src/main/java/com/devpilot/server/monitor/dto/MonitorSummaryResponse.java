package com.devpilot.server.monitor.dto;

public record MonitorSummaryResponse(
        long serverTotal,
        long serverOnline,
        long reportingServers,
        double averageCpuUsage,
        double averageMemoryUsage,
        double averageDiskUsage,
        double networkUploadRate,
        double networkDownloadRate) {
}

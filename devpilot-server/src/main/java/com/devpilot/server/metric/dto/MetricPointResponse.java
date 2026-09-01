package com.devpilot.server.metric.dto;

import java.time.LocalDateTime;

public record MetricPointResponse(
        LocalDateTime timestamp,
        Double cpuUsage,
        Double loadOne,
        Double loadFive,
        Double loadFifteen,
        Long memoryTotal,
        Long memoryUsed,
        Long memoryAvailable,
        Double memoryUsage,
        Long diskTotal,
        Long diskUsed,
        Long diskFree,
        Double diskUsage,
        Long networkBytesSent,
        Long networkBytesReceived,
        Double networkUploadRate,
        Double networkDownloadRate) {
}

package com.devpilot.server.metric.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record AgentMetricRequest(
        @Size(max = 32) String agentVersion,
        @NotNull Instant collectedAt,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double cpuUsage,
        @NotNull @PositiveOrZero Double loadOne,
        @NotNull @PositiveOrZero Double loadFive,
        @NotNull @PositiveOrZero Double loadFifteen,
        @NotNull @PositiveOrZero Long memoryTotal,
        @NotNull @PositiveOrZero Long memoryUsed,
        @NotNull @PositiveOrZero Long memoryAvailable,
        @NotNull @PositiveOrZero Long diskTotal,
        @NotNull @PositiveOrZero Long diskUsed,
        @NotNull @PositiveOrZero Long diskFree,
        @NotNull @PositiveOrZero Long networkBytesSent,
        @NotNull @PositiveOrZero Long networkBytesReceived,
        @NotNull @PositiveOrZero Double networkUploadRate,
        @NotNull @PositiveOrZero Double networkDownloadRate) {
}

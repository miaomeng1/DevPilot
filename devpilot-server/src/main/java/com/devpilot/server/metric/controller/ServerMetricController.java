package com.devpilot.server.metric.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.metric.dto.MetricHistoryResponse;
import com.devpilot.server.metric.service.MetricService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/servers/{serverId}/metrics")
@RequiredArgsConstructor
public class ServerMetricController {

    private final MetricService metricService;

    @GetMapping
    public ApiResponse<MetricHistoryResponse> history(
            @PathVariable Long serverId,
            @RequestParam(defaultValue = "1h") String range) {
        return ApiResponse.success(metricService.history(serverId, range));
    }
}

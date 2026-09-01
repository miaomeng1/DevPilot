package com.devpilot.server.metric.controller;

import com.devpilot.server.agent.controller.AgentController;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.metric.dto.AgentMetricRequest;
import com.devpilot.server.metric.dto.MetricIngestResponse;
import com.devpilot.server.metric.service.MetricService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentMetricController {

    private final MetricService metricService;

    @PostMapping("/metrics")
    public ApiResponse<MetricIngestResponse> ingest(
            @RequestHeader(name = AgentController.AGENT_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody AgentMetricRequest request) {
        return ApiResponse.success(metricService.ingest(token, request));
    }
}

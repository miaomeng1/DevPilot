package com.devpilot.server.observability;

import com.devpilot.server.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilityProperties properties;
    private final boolean otlpEnabled;

    public ObservabilityController(ObservabilityProperties properties,
                                   @Value("${management.otlp.metrics.export.enabled:false}") boolean otlpEnabled) {
        this.properties = properties;
        this.otlpEnabled = otlpEnabled;
    }

    @GetMapping("/status")
    public ApiResponse<ObservabilityStatusResponse> status() {
        return ApiResponse.success(new ObservabilityStatusResponse(properties.prometheusEnabled(),
                "/actuator/prometheus", "Bearer token", otlpEnabled, "OTLP/HTTP protobuf",
                Math.max(1, properties.snapshotIntervalMs() / 1000)));
    }
}

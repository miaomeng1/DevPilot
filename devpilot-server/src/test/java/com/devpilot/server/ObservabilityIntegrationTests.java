package com.devpilot.server;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devpilot.server.observability.DevPilotMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "devpilot.observability.prometheus-scrape-token=test-prometheus-token-with-thirty-two-bytes",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.prometheus.metrics.export.enabled=true",
        "management.otlp.metrics.export.enabled=false"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
class ObservabilityIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private DevPilotMetrics metrics;

    @Test
    void protectsPrometheusWithDedicatedTokenAndExportsPlatformMetrics() throws Exception {
        metrics.refresh();

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer test-prometheus-token-with-thirty-two-bytes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("devpilot_servers_managed")))
                .andExpect(content().string(containsString("devpilot_metrics_snapshot_success")));
    }
}

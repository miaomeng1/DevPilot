package com.devpilot.server.observability;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "devpilot.observability")
public record ObservabilityProperties(
        @Pattern(regexp = "^$|.{32,}$", message = "must be empty or contain at least 32 characters")
        String prometheusScrapeToken,
        @Min(5000) long snapshotIntervalMs) {

    public boolean prometheusEnabled() {
        return prometheusScrapeToken != null && !prometheusScrapeToken.isBlank();
    }
}

package com.devpilot.server.metric.service;

import com.devpilot.server.exception.BusinessException;
import java.time.Duration;

public enum MetricRange {
    ONE_HOUR("1h", Duration.ofHours(1), 10),
    SIX_HOURS("6h", Duration.ofHours(6), 60),
    TWENTY_FOUR_HOURS("24h", Duration.ofHours(24), 60),
    SEVEN_DAYS("7d", Duration.ofDays(7), 300);

    private final String value;
    private final Duration duration;
    private final int resolutionSeconds;

    MetricRange(String value, Duration duration, int resolutionSeconds) {
        this.value = value;
        this.duration = duration;
        this.resolutionSeconds = resolutionSeconds;
    }

    public String value() {
        return value;
    }

    public Duration duration() {
        return duration;
    }

    public int resolutionSeconds() {
        return resolutionSeconds;
    }

    public static MetricRange parse(String value) {
        for (MetricRange range : values()) {
            if (range.value.equalsIgnoreCase(value)) {
                return range;
            }
        }
        throw BusinessException.badRequest(40001, "range 仅支持 1h、6h、24h、7d");
    }
}

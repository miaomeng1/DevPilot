package com.devpilot.server.application.dto;

import java.util.List;

public record ApplicationHealthSummary(long total, long healthy, long unhealthy, long unknown) {
    public static ApplicationHealthSummary from(List<String> states) {
        long healthy = states.stream().filter("HEALTHY"::equals).count();
        long unhealthy = states.stream().filter("UNHEALTHY"::equals).count();
        return new ApplicationHealthSummary(states.size(), healthy, unhealthy, states.size() - healthy - unhealthy);
    }
}

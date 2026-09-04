package com.devpilot.server.alert.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devpilot.server.alert.entity.AlertNotificationRouteEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlertRoutingServiceTests {

    @Test
    void crossMidnightQuietHoursBelongToTheirStartingDay() {
        AlertNotificationRouteEntity route = route("23:00", "08:00", "FRIDAY");

        assertTrue(AlertRoutingService.isQuiet(route, Instant.parse("2026-09-04T15:30:00Z")));
        assertTrue(AlertRoutingService.isQuiet(route, Instant.parse("2026-09-04T23:30:00Z")));
        assertFalse(AlertRoutingService.isQuiet(route, Instant.parse("2026-09-04T14:30:00Z")));
        assertFalse(AlertRoutingService.isQuiet(route, Instant.parse("2026-09-05T15:30:00Z")));
    }

    @Test
    void sameDayQuietHoursUseTheCurrentWeekday() {
        AlertNotificationRouteEntity route = route("12:00", "13:00", "MONDAY");

        assertTrue(AlertRoutingService.isQuiet(route, Instant.parse("2026-09-07T04:30:00Z")));
        assertFalse(AlertRoutingService.isQuiet(route, Instant.parse("2026-09-07T03:30:00Z")));
    }

    private static AlertNotificationRouteEntity route(String start, String end, String days) {
        AlertNotificationRouteEntity route = new AlertNotificationRouteEntity();
        route.setQuietEnabled(1);
        route.setQuietStart(start);
        route.setQuietEnd(end);
        route.setQuietDays(days);
        route.setTimezone("Asia/Shanghai");
        return route;
    }
}

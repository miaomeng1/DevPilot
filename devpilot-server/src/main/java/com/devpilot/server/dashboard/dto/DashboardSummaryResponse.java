package com.devpilot.server.dashboard.dto;

public record DashboardSummaryResponse(
        long serverTotal,
        long serverOnline,
        long containerTotal,
        long containerRunning,
        long applicationTotal,
        long applicationUnhealthy,
        long currentAlerts,
        long todayDeployments) {
}

package com.devpilot.server.alert.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AlertRouteResponse(
        Long id,
        String name,
        Long serverId,
        String serverName,
        String minimumSeverity,
        String destinationType,
        boolean configured,
        boolean notifyResolved,
        boolean enabled,
        boolean quietEnabled,
        String quietStart,
        String quietEnd,
        List<String> quietDays,
        String timezone,
        boolean criticalBypassMute,
        boolean mutedNow,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

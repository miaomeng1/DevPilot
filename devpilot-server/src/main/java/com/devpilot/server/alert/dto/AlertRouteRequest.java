package com.devpilot.server.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AlertRouteRequest(
        @NotBlank @Size(max = 120) String name,
        Long serverId,
        @NotBlank @Pattern(regexp = "INFO|WARNING|CRITICAL") String minimumSeverity,
        @Size(max = 4000) String webhookUrl,
        boolean notifyResolved,
        boolean enabled,
        boolean quietEnabled,
        @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String quietStart,
        @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String quietEnd,
        @Size(max = 7) List<@Pattern(regexp = "MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY") String> quietDays,
        @NotBlank @Size(max = 64) String timezone,
        boolean criticalBypassMute) {
}

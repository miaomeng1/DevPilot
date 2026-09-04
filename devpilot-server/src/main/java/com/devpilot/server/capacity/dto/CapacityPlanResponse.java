package com.devpilot.server.capacity.dto;

import java.util.List;

public record CapacityPlanResponse(
        Long requiredMemoryBytes,
        Long requiredDiskBytes,
        Long recommendedServerId,
        String verdict,
        String summary,
        List<CapacityServerResponse> servers) {
}

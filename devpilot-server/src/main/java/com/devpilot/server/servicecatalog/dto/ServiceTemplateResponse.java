package com.devpilot.server.servicecatalog.dto;

import java.util.List;

public record ServiceTemplateResponse(
        String id,
        String name,
        String shortName,
        String category,
        String description,
        String image,
        String version,
        int containerPort,
        int recommendedPort,
        long memoryLimitBytes,
        List<String> persistentData,
        String documentationUrl,
        String sourceUrl,
        String setupHint,
        String accent) {
}

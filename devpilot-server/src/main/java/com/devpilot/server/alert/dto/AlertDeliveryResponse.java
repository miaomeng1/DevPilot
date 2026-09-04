package com.devpilot.server.alert.dto;

import java.time.LocalDateTime;

public record AlertDeliveryResponse(
        Long id,
        String routeName,
        String transition,
        String status,
        int attemptCount,
        Integer responseCode,
        String errorMessage,
        LocalDateTime sentAt,
        LocalDateTime updatedAt) {
}

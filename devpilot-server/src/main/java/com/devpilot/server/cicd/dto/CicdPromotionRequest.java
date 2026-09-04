package com.devpilot.server.cicd.dto;

import jakarta.validation.constraints.NotNull;

public record CicdPromotionRequest(@NotNull Long targetApplicationId) {
}

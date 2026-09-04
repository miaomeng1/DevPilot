package com.devpilot.server.capacity.controller;

import com.devpilot.server.capacity.dto.CapacityPlanResponse;
import com.devpilot.server.capacity.service.CapacityPlanningService;
import com.devpilot.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capacity")
@RequiredArgsConstructor
public class CapacityPlanningController {

    private final CapacityPlanningService service;

    @GetMapping("/plan")
    public ApiResponse<CapacityPlanResponse> plan(
            @RequestParam(defaultValue = "1073741824") long memoryBytes,
            @RequestParam(defaultValue = "5368709120") long diskBytes) {
        return ApiResponse.success(service.plan(memoryBytes, diskBytes));
    }
}

package com.devpilot.server.dashboard.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.dashboard.dto.DashboardResponse;
import com.devpilot.server.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public ApiResponse<DashboardResponse> get(@RequestParam(defaultValue = "1h") String range) {
        return ApiResponse.success(dashboardService.get(range));
    }
}

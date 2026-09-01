package com.devpilot.server.monitor.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.monitor.dto.MonitorResponse;
import com.devpilot.server.monitor.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    @GetMapping
    public ApiResponse<MonitorResponse> get(@RequestParam(defaultValue = "1h") String range) {
        return ApiResponse.success(monitorService.get(range));
    }
}

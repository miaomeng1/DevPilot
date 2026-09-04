package com.devpilot.server.alert.controller;

import com.devpilot.server.alert.dto.AlertRouteRequest;
import com.devpilot.server.alert.dto.AlertRouteResponse;
import com.devpilot.server.alert.dto.MaintenanceWindowRequest;
import com.devpilot.server.alert.dto.MaintenanceWindowResponse;
import com.devpilot.server.alert.service.AlertRoutingService;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AlertRoutingController {

    private final AlertRoutingService routingService;

    @GetMapping("/routes")
    public ApiResponse<List<AlertRouteResponse>> routes() {
        return ApiResponse.success(routingService.routes());
    }

    @PostMapping("/routes")
    public ApiResponse<AlertRouteResponse> createRoute(
            @Valid @RequestBody AlertRouteRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(routingService.createRoute(request, principal));
    }

    @PutMapping("/routes/{id}")
    public ApiResponse<AlertRouteResponse> updateRoute(@PathVariable Long id,
                                                       @Valid @RequestBody AlertRouteRequest request) {
        return ApiResponse.success(routingService.updateRoute(id, request));
    }

    @DeleteMapping("/routes/{id}")
    public ApiResponse<Void> deleteRoute(@PathVariable Long id) {
        routingService.deleteRoute(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/maintenance-windows")
    public ApiResponse<List<MaintenanceWindowResponse>> maintenanceWindows() {
        return ApiResponse.success(routingService.maintenanceWindows());
    }

    @PostMapping("/maintenance-windows")
    public ApiResponse<MaintenanceWindowResponse> createMaintenance(
            @Valid @RequestBody MaintenanceWindowRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(routingService.createMaintenance(request, principal));
    }

    @DeleteMapping("/maintenance-windows/{id}")
    public ApiResponse<Void> deleteMaintenance(@PathVariable Long id) {
        routingService.deleteMaintenance(id);
        return ApiResponse.success(null);
    }
}

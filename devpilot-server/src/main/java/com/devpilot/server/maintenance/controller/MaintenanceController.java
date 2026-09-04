package com.devpilot.server.maintenance.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.maintenance.dto.BackupOverviewResponse;
import com.devpilot.server.maintenance.dto.BackupReportResponse;
import com.devpilot.server.maintenance.dto.RestoreDrillRequest;
import com.devpilot.server.maintenance.dto.RestoreDrillResponse;
import com.devpilot.server.maintenance.service.BackupReportService;
import com.devpilot.server.maintenance.service.RestoreDrillService;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {
    private final BackupReportService backupReportService;
    private final RestoreDrillService restoreDrillService;

    @GetMapping("/backups")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BackupOverviewResponse> backups() {
        return ApiResponse.success(backupReportService.overview());
    }

    @PostMapping("/backups/report")
    public ApiResponse<BackupReportResponse> report(
            @RequestHeader(value = "X-DevPilot-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        return ApiResponse.success(backupReportService.receive(signature, body));
    }

    @PostMapping("/restore-drills")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RestoreDrillResponse> recordRestoreDrill(
            @Valid @RequestBody RestoreDrillRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(restoreDrillService.record(request, principal));
    }
}

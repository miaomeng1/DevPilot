package com.devpilot.server.settings.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.settings.dto.PublicSettingsResponse;
import com.devpilot.server.settings.dto.SystemSettingsResponse;
import com.devpilot.server.settings.dto.UpdateSystemSettingsRequest;
import com.devpilot.server.settings.service.SystemSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SystemSettingsController {

    private final SystemSettingsService settingsService;

    @GetMapping("/api/system/public-settings")
    public ApiResponse<PublicSettingsResponse> publicSettings() {
        return ApiResponse.success(settingsService.publicSettings());
    }

    @GetMapping("/api/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SystemSettingsResponse> get() { return ApiResponse.success(settingsService.get()); }

    @PutMapping("/api/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SystemSettingsResponse> update(@Valid @RequestBody UpdateSystemSettingsRequest request,
                                                      @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(settingsService.update(request, principal));
    }
}

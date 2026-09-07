package com.devpilot.server.setup;

import com.devpilot.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/setup")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PlatformSetupController {
    private final PlatformSetupService service;
    public record Configuration(@NotBlank @Size(max=36) String revision,
            @NotBlank @Size(max=1000) String publicUrl, @Size(max=1000) String providerUrl,
            @Size(max=4000) String providerApiToken, Long serverId) {
        @Override public String toString() { return "PlatformSetupConfiguration[REDACTED]"; }
    }
    @GetMapping public ApiResponse<PlatformSetupService.Status> get() { return ApiResponse.success(service.get()); }
    @PutMapping public ApiResponse<PlatformSetupService.Status> save(@Valid @RequestBody Configuration request) {
        return ApiResponse.success(service.save(request));
    }
    @PostMapping("/verify-provider") public ApiResponse<PlatformSetupService.Status> verify() {
        return ApiResponse.success(service.verifyProvider());
    }
}

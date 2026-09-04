package com.devpilot.server.servicecatalog.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.servicecatalog.dto.CreateServiceInstallationRequest;
import com.devpilot.server.servicecatalog.dto.ServiceInstallationResponse;
import com.devpilot.server.servicecatalog.dto.ServiceTemplateResponse;
import com.devpilot.server.servicecatalog.service.ServiceTemplateService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/service-templates")
@RequiredArgsConstructor
public class ServiceTemplateController {

    private final ServiceTemplateService service;

    @GetMapping
    public ApiResponse<List<ServiceTemplateResponse>> catalog() {
        return ApiResponse.success(service.catalog());
    }

    @GetMapping("/installations")
    public ApiResponse<List<ServiceInstallationResponse>> installations() {
        return ApiResponse.success(service.installations());
    }

    @PostMapping("/{templateId}/installations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServiceInstallationResponse>> install(
            @PathVariable String templateId, @Valid @RequestBody CreateServiceInstallationRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.install(templateId, request, principal)));
    }
}

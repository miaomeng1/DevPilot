package com.devpilot.server.alert.controller;

import com.devpilot.server.alert.dto.AlertEventResponse;
import com.devpilot.server.alert.dto.AlertRuleRequest;
import com.devpilot.server.alert.dto.AlertRuleResponse;
import com.devpilot.server.alert.dto.AlertSummaryResponse;
import com.devpilot.server.alert.dto.WebhookConfigRequest;
import com.devpilot.server.alert.dto.WebhookConfigResponse;
import com.devpilot.server.alert.service.AlertEventService;
import com.devpilot.server.alert.service.AlertRuleService;
import com.devpilot.server.alert.service.AlertSettingsService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRuleService ruleService;
    private final AlertEventService eventService;
    private final AlertSettingsService settingsService;

    @GetMapping("/rules")
    public ApiResponse<List<AlertRuleResponse>> rules() {
        return ApiResponse.success(ruleService.list());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AlertRuleResponse> createRule(@Valid @RequestBody AlertRuleRequest request,
                                                     @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(ruleService.create(request, principal));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AlertRuleResponse> updateRule(@PathVariable Long id,
                                                     @Valid @RequestBody AlertRuleRequest request) {
        return ApiResponse.success(ruleService.update(id, request));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        ruleService.delete(id);
        return ApiResponse.success(null);
    }

    @GetMapping
    public ApiResponse<List<AlertEventResponse>> events(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String severity,
                                                        @RequestParam(required = false) Long serverId) {
        return ApiResponse.success(eventService.list(status, severity, serverId));
    }

    @GetMapping("/summary")
    public ApiResponse<AlertSummaryResponse> summary() {
        return ApiResponse.success(eventService.summary());
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ApiResponse<AlertEventResponse> acknowledge(@PathVariable Long id,
                                                       @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(eventService.acknowledge(id, principal));
    }

    @GetMapping("/webhook")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WebhookConfigResponse> webhook() {
        return ApiResponse.success(settingsService.get());
    }

    @PutMapping("/webhook")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WebhookConfigResponse> updateWebhook(@Valid @RequestBody WebhookConfigRequest request,
                                                            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(settingsService.update(request, principal));
    }
}

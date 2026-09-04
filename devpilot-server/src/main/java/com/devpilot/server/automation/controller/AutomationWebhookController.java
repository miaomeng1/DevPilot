package com.devpilot.server.automation.controller;

import com.devpilot.server.automation.dto.AutomationDeliveryResponse;
import com.devpilot.server.automation.dto.AutomationWebhookEnabledRequest;
import com.devpilot.server.automation.dto.AutomationWebhookRequest;
import com.devpilot.server.automation.dto.AutomationWebhookResponse;
import com.devpilot.server.automation.dto.CreatedAutomationWebhookResponse;
import com.devpilot.server.automation.service.AutomationWebhookService;
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
@RequestMapping("/api/automation/webhooks")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AutomationWebhookController {
    private final AutomationWebhookService service;

    @GetMapping public ApiResponse<List<AutomationWebhookResponse>> list() {
        return ApiResponse.success(service.subscriptions());
    }

    @PostMapping
    public ApiResponse<CreatedAutomationWebhookResponse> create(@Valid @RequestBody AutomationWebhookRequest request,
                                                                @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(service.create(request, principal));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<AutomationWebhookResponse> enabled(@PathVariable Long id,
            @Valid @RequestBody AutomationWebhookEnabledRequest request) {
        return ApiResponse.success(service.setEnabled(id, request.enabled()));
    }

    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/deliveries") public ApiResponse<List<AutomationDeliveryResponse>> deliveries() {
        return ApiResponse.success(service.deliveries());
    }

    @PostMapping("/deliveries/{id}/retry") public ApiResponse<Void> retry(@PathVariable Long id) {
        service.retry(id);
        return ApiResponse.success(null);
    }
}

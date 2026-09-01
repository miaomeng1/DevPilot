package com.devpilot.server.audit.controller;

import com.devpilot.server.audit.dto.AuditPageResponse;
import com.devpilot.server.audit.service.AuditLogService;
import com.devpilot.server.common.ApiResponse;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditLogService auditService;

    @GetMapping
    public ApiResponse<AuditPageResponse> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(auditService.list(action, result, query, from, to, page, size));
    }

    @GetMapping("/actions")
    public ApiResponse<List<String>> actions() {
        return ApiResponse.success(auditService.actions());
    }
}

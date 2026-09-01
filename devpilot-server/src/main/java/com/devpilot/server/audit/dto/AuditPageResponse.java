package com.devpilot.server.audit.dto;

import java.util.List;

public record AuditPageResponse(List<AuditLogResponse> items, long total, int page, int size) {
}

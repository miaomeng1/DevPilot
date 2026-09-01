package com.devpilot.server.audit.service;

import com.devpilot.server.audit.dto.AuditLogResponse;
import com.devpilot.server.audit.dto.AuditPageResponse;
import com.devpilot.server.audit.entity.AuditLogEntity;
import com.devpilot.server.audit.mapper.AuditLogMapper;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditMapper;
    private final ServerNodeMapper serverMapper;

    public void record(AuditRecord record) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setUserId(record.userId());
            entity.setUsername(truncate(record.username(), 64));
            entity.setAction(record.action());
            entity.setResourceType(record.resourceType());
            entity.setResourceId(truncate(record.resourceId(), 100));
            entity.setResourceName(truncate(record.resourceName(), 255));
            entity.setServerId(record.serverId());
            entity.setIpAddress(truncate(record.ipAddress(), 64));
            entity.setRequestParams(truncate(record.requestParams(), 4000));
            entity.setResult(record.success() ? "SUCCESS" : "FAILED");
            entity.setErrorMessage(truncate(record.errorMessage(), 1000));
            entity.setOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
            auditMapper.insert(entity);
        } catch (RuntimeException exception) {
            log.error("Could not persist audit event {}", record.action(), exception);
        }
    }

    public AuditPageResponse list(String action, String result, String query,
                                  LocalDateTime from, LocalDateTime to, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        String safeResult = normalize(result);
        if (safeResult != null && !List.of("SUCCESS", "FAILED").contains(safeResult)) {
            safeResult = null;
        }
        String safeAction = normalize(action);
        String safeQuery = normalize(query);
        safeQuery = safeQuery == null ? null : "%" + safeQuery.toLowerCase(Locale.ROOT) + "%";
        List<AuditLogResponse> items = auditMapper.selectPage(safeAction, safeResult, safeQuery, from, to,
                safeSize, (safePage - 1) * safeSize).stream().map(this::toResponse).toList();
        long total = auditMapper.countFiltered(safeAction, safeResult, safeQuery, from, to);
        return new AuditPageResponse(items, total, safePage, safeSize);
    }

    public List<String> actions() {
        return auditMapper.selectActions();
    }

    private AuditLogResponse toResponse(AuditLogEntity entity) {
        ServerNodeEntity server = entity.getServerId() == null ? null : serverMapper.selectById(entity.getServerId());
        return new AuditLogResponse(entity.getId().toString(),
                entity.getUserId() == null ? null : entity.getUserId().toString(), entity.getUsername(),
                entity.getAction(), entity.getResourceType(), entity.getResourceId(), entity.getResourceName(),
                entity.getServerId() == null ? null : entity.getServerId().toString(),
                server == null ? null : server.getName(), entity.getIpAddress(), entity.getRequestParams(),
                entity.getResult(), entity.getErrorMessage(), entity.getOccurredAt());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maximum) {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    public record AuditRecord(Long userId, String username, String action, String resourceType,
                              String resourceId, String resourceName, Long serverId, String ipAddress,
                              String requestParams, boolean success, String errorMessage) {
    }
}

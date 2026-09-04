package com.devpilot.server.maintenance.service;

import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.maintenance.dto.RestoreDrillRequest;
import com.devpilot.server.maintenance.dto.RestoreDrillResponse;
import com.devpilot.server.maintenance.entity.BackupReportEntity;
import com.devpilot.server.maintenance.entity.RestoreDrillEntity;
import com.devpilot.server.maintenance.mapper.BackupReportMapper;
import com.devpilot.server.maintenance.mapper.RestoreDrillMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RestoreDrillService {
    private final RestoreDrillMapper drillMapper;
    private final BackupReportMapper reportMapper;

    public RestoreDrillResponse latest() {
        return response(drillMapper.selectLatest());
    }

    @Transactional
    public RestoreDrillResponse record(RestoreDrillRequest request, DevPilotPrincipal principal) {
        BackupReportEntity backup = reportMapper.selectById(request.backupReportId());
        if (backup == null) throw BusinessException.badRequest(40062, "所选备份证据不存在");
        LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
        RestoreDrillEntity entity = new RestoreDrillEntity();
        entity.setBackupReportId(backup.getId());
        entity.setBackupFileName(backup.getFileName());
        entity.setEnvironment(request.environment());
        entity.setResult(request.result());
        entity.setNotes(trimToNull(request.notes()));
        entity.setPerformedBy(principal.userId());
        entity.setPerformedByName(principal.displayName());
        entity.setPerformedAt(timestamp);
        entity.setCreatedAt(timestamp);
        drillMapper.insert(entity);
        return response(entity);
    }

    private static RestoreDrillResponse response(RestoreDrillEntity entity) {
        if (entity == null) return null;
        return new RestoreDrillResponse(entity.getId(), entity.getBackupReportId(), entity.getBackupFileName(),
                entity.getEnvironment(), entity.getResult(), entity.getNotes(), entity.getPerformedBy(),
                entity.getPerformedByName(), entity.getPerformedAt());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}

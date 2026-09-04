package com.devpilot.server.maintenance.service;

import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.maintenance.MaintenanceProperties;
import com.devpilot.server.maintenance.dto.BackupOverviewResponse;
import com.devpilot.server.maintenance.dto.BackupReportRequest;
import com.devpilot.server.maintenance.dto.BackupReportResponse;
import com.devpilot.server.maintenance.entity.BackupReportEntity;
import com.devpilot.server.maintenance.mapper.BackupReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BackupReportService {
    private final BackupReportMapper reportMapper;
    private final MaintenanceProperties properties;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final RestoreDrillService restoreDrillService;

    public BackupOverviewResponse overview() {
        List<BackupReportResponse> reports = reportMapper.selectRecent(30).stream().map(BackupReportService::response).toList();
        BackupReportResponse latest = reports.isEmpty() ? null : reports.getFirst();
        long freshnessHours = Math.max(1, properties.backupFreshness().toHours());
        Long ageHours = latest == null ? null : Math.max(0, Duration.between(latest.createdAt(), now()).toHours());
        String state = !properties.reportingConfigured() ? "NOT_CONFIGURED"
                : latest == null ? "NO_BACKUP"
                : ageHours <= freshnessHours ? "HEALTHY" : "STALE";
        return new BackupOverviewResponse(properties.reportingConfigured(), state, freshnessHours, ageHours, latest,
                restoreDrillService.latest(), reports);
    }

    @Transactional
    public BackupReportResponse receive(String signature, byte[] rawBody) {
        if (!properties.reportingConfigured()) {
            throw BusinessException.unauthorized("备份报告功能尚未配置");
        }
        verifySignature(signature, rawBody);
        BackupReportRequest request;
        try {
            request = objectMapper.readValue(rawBody, BackupReportRequest.class);
        } catch (Exception exception) {
            throw BusinessException.badRequest(40060, "备份报告 JSON 无效");
        }
        List<String> violations = validator.validate(request).stream().map(ConstraintViolation::getMessage).toList();
        if (!violations.isEmpty()) {
            throw BusinessException.badRequest(40060, "备份报告字段无效: " + violations.getFirst());
        }
        LocalDateTime timestamp = now();
        if (request.createdAt().isAfter(timestamp.plusMinutes(5))) {
            throw BusinessException.badRequest(40061, "备份时间不能晚于服务器时间");
        }
        String checksum = request.sha256().toLowerCase();
        BackupReportEntity existing = reportMapper.selectBySha256(checksum);
        if (existing != null) return response(existing);

        BackupReportEntity entity = new BackupReportEntity();
        entity.setFileName(request.fileName());
        entity.setSizeBytes(request.sizeBytes());
        entity.setSha256(checksum);
        entity.setDestinationType(request.destinationType());
        entity.setCreatedAt(request.createdAt());
        entity.setVerifiedAt(timestamp);
        entity.setReportedAt(timestamp);
        try {
            reportMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            BackupReportEntity concurrent = reportMapper.selectBySha256(checksum);
            if (concurrent != null) return response(concurrent);
            throw exception;
        }
        return response(entity);
    }

    private void verifySignature(String signature, byte[] body) {
        try {
            if (signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}")) {
                throw BusinessException.unauthorized("备份报告签名缺失");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.reportSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] supplied = HexFormat.of().parseHex(signature.substring(7));
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw BusinessException.unauthorized("备份报告签名无效");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unauthorized("备份报告签名无效");
        }
    }

    private static BackupReportResponse response(BackupReportEntity entity) {
        return new BackupReportResponse(entity.getId(), entity.getFileName(), entity.getSizeBytes(), entity.getSha256(),
                entity.getDestinationType(), entity.getCreatedAt(), entity.getVerifiedAt(), entity.getReportedAt());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

package com.devpilot.server.settings.service;

import com.devpilot.server.agent.service.AgentProperties;
import com.devpilot.server.alert.dto.WebhookConfigRequest;
import com.devpilot.server.alert.dto.WebhookConfigResponse;
import com.devpilot.server.alert.entity.SystemSettingEntity;
import com.devpilot.server.alert.mapper.SystemSettingMapper;
import com.devpilot.server.alert.service.AlertSettingsService;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SecurityProperties;
import com.devpilot.server.settings.dto.PublicSettingsResponse;
import com.devpilot.server.settings.dto.SystemSettingsResponse;
import com.devpilot.server.settings.dto.UpdateSystemSettingsRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private static final String SYSTEM_NAME = "SYSTEM_NAME";
    private static final String LOGO_URL = "LOGO_URL";
    private static final String DEFAULT_THEME = "DEFAULT_THEME";
    private static final String ACCESS_TTL = "ACCESS_TOKEN_TTL_MINUTES";
    private static final String REFRESH_TTL = "REFRESH_TOKEN_TTL_HOURS";
    private static final String HEARTBEAT_TIMEOUT = "AGENT_HEARTBEAT_TIMEOUT_SECONDS";
    private static final String METRIC_INTERVAL = "METRIC_INTERVAL_SECONDS";
    private static final String LOG_LINES = "LOG_DEFAULT_LINES";

    private final SystemSettingMapper settingMapper;
    private final AlertSettingsService alertSettingsService;
    private final SecurityProperties securityProperties;
    private final AgentProperties agentProperties;

    public PublicSettingsResponse publicSettings() {
        return new PublicSettingsResponse(value(SYSTEM_NAME, "DevPilot"), blankToNull(value(LOGO_URL, null)),
                value(DEFAULT_THEME, "DARK"), integer(LOG_LINES, 100));
    }

    public SystemSettingsResponse get() {
        WebhookConfigResponse webhook = alertSettingsService.get();
        return new SystemSettingsResponse(value(SYSTEM_NAME, "DevPilot"), blankToNull(value(LOGO_URL, null)),
                value(DEFAULT_THEME, "DARK"), accessTokenTtlMinutes(), refreshTokenTtlHours(),
                heartbeatTimeoutSeconds(), metricIntervalSeconds(), integer(LOG_LINES, 100),
                webhook.enabled(), webhook.configured(), webhook.destinationType());
    }

    @Transactional
    public SystemSettingsResponse update(UpdateSystemSettingsRequest request, DevPilotPrincipal principal) {
        validateLogo(request.logoUrl());
        save(SYSTEM_NAME, request.systemName().trim(), principal.userId());
        save(LOGO_URL, blankToNull(request.logoUrl()), principal.userId());
        save(DEFAULT_THEME, request.defaultTheme(), principal.userId());
        save(ACCESS_TTL, request.accessTokenTtlMinutes().toString(), principal.userId());
        save(REFRESH_TTL, request.refreshTokenTtlHours().toString(), principal.userId());
        save(HEARTBEAT_TIMEOUT, request.agentHeartbeatTimeoutSeconds().toString(), principal.userId());
        save(METRIC_INTERVAL, request.metricIntervalSeconds().toString(), principal.userId());
        save(LOG_LINES, request.logDefaultLines(), principal.userId());
        alertSettingsService.update(new WebhookConfigRequest(request.webhookEnabled(), request.webhookUrl()), principal);
        return get();
    }

    public int accessTokenTtlMinutes() {
        return bounded(ACCESS_TTL, Math.toIntExact(securityProperties.accessTokenTtl().toMinutes()), 5, 1440);
    }

    public int refreshTokenTtlHours() {
        return bounded(REFRESH_TTL, Math.toIntExact(securityProperties.refreshTokenTtl().toHours()), 1, 2160);
    }

    public int heartbeatTimeoutSeconds() {
        return bounded(HEARTBEAT_TIMEOUT, Math.toIntExact(agentProperties.heartbeatTimeout().toSeconds()), 15, 600);
    }

    public int metricIntervalSeconds() {
        return bounded(METRIC_INTERVAL, 10, 5, 300);
    }

    public Duration accessTokenTtl() { return Duration.ofMinutes(accessTokenTtlMinutes()); }
    public Duration refreshTokenTtl() { return Duration.ofHours(refreshTokenTtlHours()); }

    private void save(String key, String settingValue, Long userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SystemSettingEntity entity = settingMapper.selectByKey(key);
        if (entity == null) {
            entity = new SystemSettingEntity();
            entity.setSettingKey(key);
            entity.setSensitiveValue(0);
            entity.setCreatedAt(now);
        }
        entity.setSettingValue(settingValue);
        entity.setUpdatedBy(userId);
        entity.setUpdatedAt(now);
        if (entity.getId() == null) settingMapper.insert(entity); else settingMapper.updateById(entity);
    }

    private String value(String key, String fallback) {
        SystemSettingEntity entity = settingMapper.selectByKey(key);
        return entity == null || entity.getSettingValue() == null ? fallback : entity.getSettingValue();
    }

    private int integer(String key, int fallback) {
        try { return Integer.parseInt(value(key, Integer.toString(fallback))); }
        catch (NumberFormatException exception) { return fallback; }
    }

    private int bounded(String key, int fallback, int min, int max) {
        int value = integer(key, fallback);
        return value < min || value > max ? fallback : value;
    }

    private static void validateLogo(String value) {
        String candidate = blankToNull(value);
        if (candidate == null || candidate.startsWith("/")) return;
        try {
            URI uri = new URI(candidate);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new URISyntaxException(candidate, "invalid logo URL");
            }
        } catch (URISyntaxException exception) {
            throw BusinessException.badRequest(40050, "Logo 必须是站内路径或有效的 HTTP(S) URL");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

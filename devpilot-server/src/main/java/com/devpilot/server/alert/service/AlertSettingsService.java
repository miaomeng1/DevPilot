package com.devpilot.server.alert.service;

import com.devpilot.server.alert.dto.WebhookConfigRequest;
import com.devpilot.server.alert.dto.WebhookConfigResponse;
import com.devpilot.server.alert.entity.SystemSettingEntity;
import com.devpilot.server.alert.mapper.SystemSettingMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SensitiveSettingCipher;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertSettingsService {

    private static final String ENABLED_KEY = "ALERT_WEBHOOK_ENABLED";
    private static final String URL_KEY = "ALERT_WEBHOOK_URL";
    private final SystemSettingMapper settingMapper;
    private final SensitiveSettingCipher cipher;

    public WebhookConfigResponse get() {
        String url = webhookUrl();
        return new WebhookConfigResponse(isEnabled(), url != null, destinationType(url));
    }

    @Transactional
    public WebhookConfigResponse update(WebhookConfigRequest request, DevPilotPrincipal principal) {
        String candidate = trimToNull(request.url());
        SystemSettingEntity existingUrl = settingMapper.selectByKey(URL_KEY);
        if (candidate != null) {
            validateUrl(candidate);
            save(URL_KEY, cipher.encrypt(candidate), true, principal.userId());
        } else if (request.enabled() && existingUrl == null) {
            throw BusinessException.badRequest(40032, "启用 Webhook 前必须提供 URL");
        }
        save(ENABLED_KEY, Boolean.toString(request.enabled()), false, principal.userId());
        return get();
    }

    public boolean isEnabled() {
        SystemSettingEntity setting = settingMapper.selectByKey(ENABLED_KEY);
        return setting != null && Boolean.parseBoolean(setting.getSettingValue());
    }

    public String webhookUrl() {
        SystemSettingEntity setting = settingMapper.selectByKey(URL_KEY);
        if (setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return null;
        }
        return cipher.decrypt(setting.getSettingValue());
    }

    private void save(String key, String value, boolean sensitive, Long userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SystemSettingEntity entity = settingMapper.selectByKey(key);
        if (entity == null) {
            entity = new SystemSettingEntity();
            entity.setSettingKey(key);
            entity.setCreatedAt(now);
        }
        entity.setSettingValue(value);
        entity.setSensitiveValue(sensitive ? 1 : 0);
        entity.setUpdatedBy(userId);
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            settingMapper.insert(entity);
        } else {
            settingMapper.updateById(entity);
        }
    }

    static void validateUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new URISyntaxException(value, "Webhook must be an HTTP(S) URL without userinfo");
            }
        } catch (URISyntaxException exception) {
            throw BusinessException.badRequest(40032, "Webhook URL 必须是有效的 HTTP(S) 地址");
        }
    }

    static String destinationType(String url) {
        if (url == null) {
            return "NONE";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("discord.com/api/webhooks") || lower.contains("discordapp.com/api/webhooks")) {
            return "DISCORD";
        }
        if (lower.contains("open.feishu.cn") || lower.contains("open.larksuite.com")) {
            return "FEISHU";
        }
        if (lower.contains("qyapi.weixin.qq.com")) {
            return "WECHAT_WORK";
        }
        return "CUSTOM";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

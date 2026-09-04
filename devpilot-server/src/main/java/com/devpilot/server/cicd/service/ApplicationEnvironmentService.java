package com.devpilot.server.cicd.service;

import com.devpilot.server.application.entity.ApplicationEntity;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.dto.ApplicationEnvironmentResponse;
import com.devpilot.server.cicd.dto.ApplicationEnvironmentVariableRequest;
import com.devpilot.server.cicd.dto.ApplicationEnvironmentVariableResponse;
import com.devpilot.server.cicd.dto.SaveApplicationEnvironmentRequest;
import com.devpilot.server.cicd.entity.ApplicationEnvironmentStateEntity;
import com.devpilot.server.cicd.entity.ApplicationEnvironmentVariableEntity;
import com.devpilot.server.cicd.entity.CicdConfigurationEntity;
import com.devpilot.server.cicd.mapper.ApplicationEnvironmentStateMapper;
import com.devpilot.server.cicd.mapper.ApplicationEnvironmentVariableMapper;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.cicd.service.DeploymentWebhookClient.EnvironmentVariable;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationEnvironmentService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final ApplicationMapper applicationMapper;
    private final CicdConfigurationMapper configurationMapper;
    private final ApplicationEnvironmentStateMapper stateMapper;
    private final ApplicationEnvironmentVariableMapper variableMapper;
    private final SensitiveSettingCipher cipher;
    private final DeploymentWebhookClient providerClient;
    private final ObjectMapper objectMapper;

    public ApplicationEnvironmentResponse get(Long applicationId) {
        requireApplication(applicationId);
        return response(applicationId, stateMapper.selectById(applicationId));
    }

    @Transactional
    public ApplicationEnvironmentResponse save(Long applicationId, SaveApplicationEnvironmentRequest request,
                                               DevPilotPrincipal principal) {
        requireApplicationForUpdate(applicationId);
        ApplicationEnvironmentStateEntity state = stateMapper.selectForUpdate(applicationId);
        int currentRevision = state == null ? 0 : state.getRevision();
        if (currentRevision != request.expectedRevision()) {
            throw BusinessException.conflict(40950, "环境变量已被其他会话修改，请刷新后重新确认差异");
        }
        List<ApplicationEnvironmentVariableEntity> existingRows = variableMapper.selectByApplicationId(applicationId);
        Map<String, ApplicationEnvironmentVariableEntity> existing = new LinkedHashMap<>();
        existingRows.forEach(row -> existing.put(row.getVariableKey(), row));

        Set<String> keys = new LinkedHashSet<>();
        List<ApplicationEnvironmentVariableEntity> replacements = new ArrayList<>();
        LocalDateTime timestamp = now();
        for (ApplicationEnvironmentVariableRequest candidate : request.variables()) {
            String key = candidate.key().trim();
            if (!keys.add(key)) {
                throw BusinessException.badRequest(40050, "环境变量 Key 重复: " + key);
            }
            ApplicationEnvironmentVariableEntity previous = existing.get(key);
            String valueCipher;
            if (candidate.value() == null) {
                if (previous == null || previous.getSecret() != 1 || !candidate.secret()) {
                    throw BusinessException.badRequest(40051, key + " 必须提供值；只有现有 Secret 可以留空保留");
                }
                valueCipher = previous.getValueCipher();
            } else {
                valueCipher = cipher.encrypt(candidate.value());
            }
            ApplicationEnvironmentVariableEntity replacement = new ApplicationEnvironmentVariableEntity();
            replacement.setApplicationId(applicationId);
            replacement.setVariableKey(key);
            replacement.setValueCipher(valueCipher);
            replacement.setSecret(candidate.secret() ? 1 : 0);
            replacement.setDescription(trimToNull(candidate.description()));
            replacement.setCreatedAt(previous == null ? timestamp : previous.getCreatedAt());
            replacement.setUpdatedAt(timestamp);
            replacements.add(replacement);
        }
        variableMapper.deleteByApplicationId(applicationId);
        replacements.forEach(variableMapper::insert);

        boolean create = state == null;
        if (create) {
            state = new ApplicationEnvironmentStateEntity();
            state.setApplicationId(applicationId);
            state.setRevision(0);
            state.setLastSyncedKeysJson("[]");
            state.setCreatedAt(timestamp);
        }
        state.setRevision(currentRevision + 1);
        state.setSyncStatus("DIRTY");
        state.setSyncError(null);
        state.setUpdatedBy(principal.userId());
        state.setUpdatedAt(timestamp);
        if (create) stateMapper.insert(state); else stateMapper.updateById(state);
        return response(applicationId, state);
    }

    @Transactional
    public ApplicationEnvironmentResponse sync(Long applicationId) {
        requireApplicationForUpdate(applicationId);
        ApplicationEnvironmentStateEntity state = stateMapper.selectForUpdate(applicationId);
        if (state == null) return response(applicationId, null);
        CicdConfigurationEntity configuration = configurationMapper.selectByApplicationId(applicationId);
        sync(configuration, state);
        return response(applicationId, state);
    }

    public void syncForDeployment(CicdConfigurationEntity configuration) {
        ApplicationEnvironmentStateEntity state = stateMapper.selectForUpdate(configuration.getApplicationId());
        if (state == null || (state.getSyncedRevision() != null
                && state.getSyncedRevision().equals(state.getRevision()))) return;
        sync(configuration, state);
        if (!"SYNCED".equals(state.getSyncStatus())) {
            throw new IllegalStateException(state.getSyncError());
        }
    }

    private void sync(CicdConfigurationEntity configuration, ApplicationEnvironmentStateEntity state) {
        LocalDateTime timestamp = now();
        try {
            if (configuration == null) throw new IllegalStateException("请先保存 CI/CD 部署配置");
            List<ApplicationEnvironmentVariableEntity> rows = variableMapper
                    .selectByApplicationId(configuration.getApplicationId());
            Set<String> previouslyManaged = new LinkedHashSet<>(readKeys(state.getLastSyncedKeysJson()));
            if (rows.isEmpty() && previouslyManaged.isEmpty()) {
                markSynced(state, List.of(), timestamp);
                return;
            }
            if (!"API".equals(configuration.getDeploymentMode())
                    || !"COOLIFY".equals(configuration.getDeploymentProvider())) {
                throw new IllegalStateException("环境变量安全自动同步目前仅支持 Coolify API 模式；"
                        + "Dokploy 的整段替换接口可能删除平台中既有 Secret，DevPilot 已阻止该操作");
            }
            Map<String, EnvironmentVariable> desired = new LinkedHashMap<>();
            for (ApplicationEnvironmentVariableEntity row : rows) {
                desired.put(row.getVariableKey(), new EnvironmentVariable(
                        cipher.decrypt(row.getValueCipher()), row.getSecret() == 1));
            }
            providerClient.syncCoolifyEnvironment(
                    cipher.decrypt(configuration.getProviderBaseUrlCipher()),
                    cipher.decrypt(configuration.getProviderApiTokenCipher()),
                    configuration.getProviderResourceId(), desired, previouslyManaged);
            markSynced(state, new ArrayList<>(desired.keySet()), timestamp);
        } catch (Exception exception) {
            state.setSyncStatus("FAILED");
            state.setSyncError(truncate(valueOr(exception.getMessage(), "环境变量同步失败"), 1000));
            state.setUpdatedAt(timestamp);
            stateMapper.updateById(state);
        }
    }

    private void markSynced(ApplicationEnvironmentStateEntity state, List<String> keys, LocalDateTime timestamp) {
        state.setSyncedRevision(state.getRevision());
        state.setLastSyncedKeysJson(writeKeys(keys));
        state.setSyncStatus("SYNCED");
        state.setSyncError(null);
        state.setProviderSyncedAt(timestamp);
        state.setUpdatedAt(timestamp);
        stateMapper.updateById(state);
    }

    private ApplicationEnvironmentResponse response(Long applicationId, ApplicationEnvironmentStateEntity state) {
        List<ApplicationEnvironmentVariableResponse> variables = variableMapper.selectByApplicationId(applicationId)
                .stream().map(row -> new ApplicationEnvironmentVariableResponse(
                        row.getVariableKey(), row.getSecret() == 1 ? null : cipher.decrypt(row.getValueCipher()),
                        row.getSecret() == 1, true, row.getDescription())).toList();
        return new ApplicationEnvironmentResponse(applicationId, state == null ? 0 : state.getRevision(),
                state == null ? null : state.getSyncedRevision(), variables,
                state == null ? "NOT_CONFIGURED" : state.getSyncStatus(),
                state == null ? null : state.getSyncError(),
                state == null ? null : state.getProviderSyncedAt(), state == null ? null : state.getUpdatedAt());
    }

    private List<String> readKeys(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeKeys(List<String> keys) {
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存环境变量同步状态", exception);
        }
    }

    private void requireApplication(Long applicationId) {
        ApplicationEntity application = applicationMapper.selectById(applicationId);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
    }

    private void requireApplicationForUpdate(Long applicationId) {
        ApplicationEntity application = applicationMapper.selectByIdForUpdate(applicationId);
        if (application == null) throw BusinessException.notFound(40420, "应用不存在");
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int maximum) {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

package com.devpilot.server.setup;

import com.devpilot.server.agent.service.AgentProperties;
import com.devpilot.server.cicd.onboarding.OnboardingHttpClient;
import com.devpilot.server.cicd.onboarding.ProviderOnboardingClient;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.SensitiveSettingCipher;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlatformSetupService {
    private final JdbcTemplate jdbc;
    private final AgentProperties agentProperties;
    private final ServerNodeMapper servers;
    private final ProviderOnboardingClient providers;
    private final SensitiveSettingCipher cipher;

    private record State(String publicUrl, String providerUrl, String tokenCipher, Long serverId,
                         String revision, LocalDateTime verifiedAt, String error) { }
    public record Status(String publicUrl, String providerUrl, boolean providerTokenConfigured, Long serverId,
                         String publicUrlStatus, String providerStatus, String serverStatus, String agentStatus,
                         LocalDateTime providerVerifiedAt, LocalDateTime lastHeartbeat, String providerError,
                         String revision) { }

    private State state(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM platform_setup WHERE id=1" + (lock ? " FOR UPDATE" : ""), (rs, index) ->
                new State(rs.getString("public_url"), rs.getString("provider_url"), rs.getString("provider_token_cipher"),
                        rs.getObject("server_id", Long.class), rs.getString("revision"),
                        rs.getObject("provider_verified_at", LocalDateTime.class), rs.getString("provider_error")));
    }

    public String publicUrl() {
        var state = state(false);
        return state.publicUrl() == null ? agentProperties.publicUrl() : state.publicUrl();
    }
    public record InternalConnection(String url, String token) {
        @Override public String toString() { return "InternalConnection[REDACTED]"; }
    }
    public InternalConnection connection(String expectedRevision) {
        var saved = state(false);
        if (expectedRevision == null || !expectedRevision.equals(saved.revision())) {
            throw BusinessException.conflict(40975, "初始化连接配置已改变，请刷新后重新识别项目");
        }
        if (saved.providerUrl() == null || saved.tokenCipher() == null) throw BusinessException.badRequest(40075, "请先在初始化向导保存 Dokploy 授权");
        return new InternalConnection(saved.providerUrl(), cipher.decrypt(saved.tokenCipher()));
    }

    public Status get() {
        var state = state(false);
        var node = state.serverId() == null ? null : servers.selectActiveById(state.serverId());
        LocalDateTime observedAt = now();
        String providerStatus = state.tokenCipher() == null ? "NOT_CONFIGURED" : state.error() != null ? "FAILED"
                : state.verifiedAt() != null && state.verifiedAt().isAfter(observedAt.plusSeconds(30)) ? "MANUAL_REQUIRED"
                : state.verifiedAt() != null && state.verifiedAt().isAfter(observedAt.minusMinutes(15)) ? "VERIFIED" : "SAVED_UNVERIFIED";
        String agentStatus = node == null || node.getLastHeartbeat() == null ? "NOT_CONFIGURED"
                : "OFFLINE".equals(node.getAgentStatus()) ? "FAILED"
                : node.getLastHeartbeat().isAfter(observedAt.plusSeconds(30)) ? "MANUAL_REQUIRED"
                : !node.getLastHeartbeat().isAfter(observedAt.minus(agentProperties.heartbeatTimeout())) ? "FAILED"
                : "ONLINE".equals(node.getAgentStatus()) ? "VERIFIED" : "SAVED_UNVERIFIED";
        return new Status(publicUrl(), state.providerUrl(), state.tokenCipher() != null, state.serverId(),
                state.publicUrl() == null ? "NOT_CONFIGURED" : "MANUAL_REQUIRED", providerStatus,
                state.serverId() == null ? "NOT_CONFIGURED" : node == null ? "FAILED" : "VERIFIED", agentStatus,
                state.verifiedAt(), node == null ? null : node.getLastHeartbeat(), state.error(), state.revision());
    }

    @Transactional
    public Status save(PlatformSetupController.Configuration request) {
        var old = state(true);
        if (!old.revision().equals(request.revision())) throw BusinessException.conflict(40975, "配置已被其他操作更新，请刷新后重试");
        String publicUrl = origin(request.publicUrl());
        String providerUrl = request.providerUrl() == null || request.providerUrl().isBlank() ? null
                : origin(request.providerUrl());
        if (request.serverId() != null && servers.selectActiveById(request.serverId()) == null) throw BusinessException.notFound(40401, "服务器不存在");
        boolean newToken = request.providerApiToken() != null && !request.providerApiToken().isBlank();
        boolean changed = !java.util.Objects.equals(providerUrl, old.providerUrl()) || newToken;
        if (providerUrl != null && !java.util.Objects.equals(providerUrl, old.providerUrl()) && !newToken) {
            throw BusinessException.badRequest(40075, "变更部署平台地址时必须重新提供 Key，不能将旧 Key 发送到新地址");
        }
        String token = providerUrl == null ? null : newToken ? cipher.encrypt(request.providerApiToken()) : old.tokenCipher();
        jdbc.update("UPDATE platform_setup SET public_url=?, provider_url=?, provider_token_cipher=?, server_id=?, revision=?, provider_verified_at=?, provider_error=? WHERE id=1",
                publicUrl, providerUrl, token, request.serverId(), UUID.randomUUID().toString(),
                changed ? null : old.verifiedAt(), changed ? null : old.error());
        return get();
    }

    public Status verifyProvider() {
        var state = state(false);
        if (state.tokenCipher() == null) throw BusinessException.badRequest(40075, "请先保存 Dokploy 地址和 Key");
        String error = null;
        try { providers.discover("DOKPLOY", state.providerUrl(), cipher.decrypt(state.tokenCipher())); }
        catch (Exception failure) { error = "无法读取 Dokploy 项目和服务器，请检查网络、地址及 Key 权限"; }
        int updated = jdbc.update("UPDATE platform_setup SET provider_verified_at=?, provider_error=? WHERE id=1 AND revision=?",
                error == null ? now() : null, error, state.revision());
        if (updated == 0) throw BusinessException.conflict(40975, "验证期间配置已改变，请重新验证");
        return get();
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static String origin(String value) {
        try {
            var uri = java.net.URI.create(value);
            if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getPort() > 65535 || uri.getPort() == 0) throw new IllegalArgumentException();
            return OnboardingHttpClient.origin(value, false);
        }
        catch (IllegalArgumentException error) { throw BusinessException.badRequest(40075, "地址必须为 HTTP(S) 根地址，不能包含账号、路径、查询参数或片段"); }
    }
}

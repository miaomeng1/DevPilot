package com.devpilot.server.node.service;

import com.devpilot.server.agent.service.AgentProperties;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.dto.CreateServerRequest;
import com.devpilot.server.node.dto.CreateServerResponse;
import com.devpilot.server.node.dto.ServerNodeResponse;
import com.devpilot.server.node.entity.AgentTokenEntity;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.AgentTokenMapper;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SecretHashing;
import com.devpilot.server.servicecatalog.mapper.ServiceInstallationMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServerNodeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final ServerNodeMapper serverNodeMapper;
    private final AgentTokenMapper agentTokenMapper;
    private final AgentProperties agentProperties;
    private final ServiceInstallationMapper serviceInstallationMapper;
    private final com.devpilot.server.setup.PlatformSetupService platformSetup;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.devpilot.server.security.SensitiveSettingCipher cipher;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public List<ServerNodeResponse> list() {
        return serverNodeMapper.selectAllActive().stream().map(ServerNodeResponse::from).toList();
    }

    public ServerNodeResponse get(Long id) {
        ServerNodeEntity entity = serverNodeMapper.selectActiveById(id);
        if (entity == null) {
            throw BusinessException.notFound(40401, "服务器不存在");
        }
        return ServerNodeResponse.from(entity);
    }

    @Transactional
    public CreateServerResponse create(CreateServerRequest request, DevPilotPrincipal principal) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String requestId = request.requestId() == null ? null : request.requestId().toLowerCase(java.util.Locale.ROOT);
        if (requestId != null) {
            // Lock an existing owner row, including when this request key does not yet exist.
            jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE", Long.class, principal.userId());
            var previous = jdbc.query("SELECT * FROM server_creation_request WHERE created_by=? AND request_id=?",
                    (rs, index) -> new Creation(rs.getLong("server_id"), rs.getString("requested_name"),
                            rs.getString("response_cipher"), rs.getObject("expires_at", LocalDateTime.class)), principal.userId(), requestId);
            if (!previous.isEmpty()) {
                var saved = previous.getFirst();
                if (!saved.name().equals(request.name().trim())) throw BusinessException.conflict(40976, "同一创建请求不能改变服务器名称");
                var existing = serverNodeMapper.selectActiveById(saved.serverId());
                if (existing == null) throw BusinessException.conflict(40976, "原请求的服务器已删除，不会自动重新创建");
                if (saved.responseCipher() == null || !saved.expiresAt().isAfter(now)) {
                    throw BusinessException.conflict(40976, "此请求已创建服务器 " + saved.serverId() + "；安装凭据恢复窗口已过期，请核对已有服务器，不要重复创建");
                }
                try {
                    var response = json.readValue(cipher.decrypt(saved.responseCipher()), CreateServerResponse.class);
                    var issued = agentTokenMapper.selectByHash(SecretHashing.sha256(response.agentToken()));
                    if (issued == null || issued.getRevokedAt() != null || "REVOKED".equals(issued.getStatus())) {
                        throw BusinessException.conflict(40976, "原安装凭据已撤销，不会重放或重新创建服务器");
                    }
                    return new CreateServerResponse(ServerNodeResponse.from(existing), response.agentToken(), response.installCommand());
                } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                    throw new IllegalStateException("Cannot read saved creation response", error);
                }
            }
        }
        ServerNodeEntity node = new ServerNodeEntity();
        node.setName(request.name().trim());
        node.setAgentStatus("UNKNOWN");
        node.setCreatedBy(principal.userId());
        node.setDeleted(0);
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        serverNodeMapper.insert(node);

        String rawToken = generateToken();
        AgentTokenEntity token = new AgentTokenEntity();
        token.setServerId(node.getId());
        token.setTokenPrefix(rawToken.substring(0, Math.min(18, rawToken.length())));
        token.setTokenHash(SecretHashing.sha256(rawToken));
        token.setStatus("PENDING");
        token.setCreatedBy(principal.userId());
        token.setCreatedAt(now);
        agentTokenMapper.insert(token);

        String publicUrl = withoutTrailingSlash(platformSetup.publicUrl());
        String installCommand = "curl -fsSL " + shellQuote(publicUrl + "/install-agent.sh")
                + " | bash -s -- --server " + shellQuote(publicUrl)
                + " --token " + shellQuote(rawToken);
        var response = new CreateServerResponse(ServerNodeResponse.from(node), rawToken, installCommand);
        if (requestId != null) {
            try {
                jdbc.update("INSERT INTO server_creation_request(created_by,request_id,server_id,requested_name,response_cipher,expires_at) VALUES(?,?,?,?,?,?)",
                        principal.userId(), requestId, node.getId(), node.getName(), cipher.encrypt(json.writeValueAsString(response)), now.plusHours(24));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Cannot save creation response", error);
            }
        }
        return response;
    }

    private record Creation(Long serverId, String name, String responseCipher, LocalDateTime expiresAt) { }

    public record CreationState(String serverId, String name, String status) { }

    public CreationState creationState(java.util.UUID requestId, DevPilotPrincipal principal) {
        var requests = jdbc.query("SELECT server_id,requested_name,response_cipher IS NOT NULL AS recoverable,expires_at FROM server_creation_request WHERE created_by=? AND request_id=?",
                (rs, row) -> {
                    long id = rs.getLong("server_id");
                    String state = !rs.getObject("expires_at", LocalDateTime.class).isAfter(LocalDateTime.now(ZoneOffset.UTC))
                            ? "EXPIRED" : rs.getBoolean("recoverable") ? "AVAILABLE" : "UNAVAILABLE";
                    if (serverNodeMapper.selectActiveById(id) == null) state = "DELETED";
                    return new CreationState(Long.toString(id), rs.getString("requested_name"), state);
                }, principal.userId(), requestId.toString());
        if (requests.isEmpty()) throw BusinessException.notFound(40401, "未找到当前管理员的创建结果；请保留原请求并重试，不要直接新建");
        return requests.getFirst();
    }

    public record RegistrationState(String revision) { }

    public RegistrationState registration(Long id) {
        get(id);
        return new RegistrationState(tokenRevision(id));
    }

    private String tokenRevision(Long id) {
        var ids = jdbc.query("SELECT id FROM agent_token WHERE server_id=? ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getString(1), id);
        return ids.isEmpty() ? "none" : ids.getFirst();
    }

    @Transactional
    public CreateServerResponse renewToken(Long id, com.devpilot.server.node.dto.RenewAgentTokenRequest request,
                                          DevPilotPrincipal principal) {
        if (!request.confirmed()) throw BusinessException.conflict(40977, "必须明确确认撤销旧 Token");
        String key = request.requestId().toLowerCase(java.util.Locale.ROOT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE", Long.class, principal.userId());
        var node = serverNodeMapper.lockActiveById(id);
        if (node == null) throw BusinessException.notFound(40401, "服务器不存在");
        var saved = jdbc.query("SELECT * FROM agent_token_renewal_request WHERE created_by=? AND request_id=?",
                (rs, row) -> new Creation(rs.getLong("server_id"), rs.getString("expected_revision"),
                        rs.getString("response_cipher"), rs.getObject("expires_at", LocalDateTime.class)), principal.userId(), key);
        if (!saved.isEmpty()) {
            var previous = saved.getFirst();
            if (!previous.serverId().equals(id) || !previous.name().equals(request.expectedRevision())) {
                throw BusinessException.conflict(40977, "同一重新签发请求不能改变服务器或凭据版本");
            }
            if (previous.responseCipher() == null || !previous.expiresAt().isAfter(now)) {
                throw BusinessException.conflict(40977, "该次签发的恢复窗口已结束或凭据已被替换，请重新核对服务器后确认新的签发操作");
            }
            try {
                var response = json.readValue(cipher.decrypt(previous.responseCipher()), CreateServerResponse.class);
                var token = agentTokenMapper.selectByHash(SecretHashing.sha256(response.agentToken()));
                if (token == null || token.getRevokedAt() != null || "REVOKED".equals(token.getStatus())) {
                    throw BusinessException.conflict(40977, "该次签发的 Token 已撤销");
                }
                return new CreateServerResponse(ServerNodeResponse.from(node), response.agentToken(), response.installCommand());
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Cannot read renewal response", error);
            }
        }
        if (!tokenRevision(id).equals(request.expectedRevision())) {
            throw BusinessException.conflict(40977, "凭据已被其他操作更新，请刷新并重新确认；本次未轮换 Token");
        }
        if (serviceInstallationMapper.countInProgressByServer(id) > 0) {
            throw BusinessException.conflict(40967, "服务器仍有进行中的服务模板安装，请等待任务结束");
        }
        agentTokenMapper.revokeByServer(id, now);
        jdbc.update("UPDATE server_creation_request SET response_cipher=NULL WHERE server_id=?", id);
        jdbc.update("UPDATE agent_token_renewal_request SET response_cipher=NULL WHERE server_id=?", id);
        String raw = generateToken();
        var token = new AgentTokenEntity();
        token.setServerId(id);
        token.setTokenPrefix(raw.substring(0, 18));
        token.setTokenHash(SecretHashing.sha256(raw));
        token.setStatus("PENDING");
        token.setCreatedBy(principal.userId());
        token.setCreatedAt(now);
        agentTokenMapper.insert(token);
        String url = withoutTrailingSlash(platformSetup.publicUrl());
        var response = new CreateServerResponse(ServerNodeResponse.from(node), raw,
                "curl -fsSL " + shellQuote(url + "/install-agent.sh") + " | bash -s -- --server " + shellQuote(url)
                        + " --token " + shellQuote(raw));
        try {
            jdbc.update("INSERT INTO agent_token_renewal_request(created_by,request_id,server_id,expected_revision,response_cipher,expires_at) VALUES(?,?,?,?,?,?)",
                    principal.userId(), key, id, request.expectedRevision(), cipher.encrypt(json.writeValueAsString(response)), now.plusHours(24));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Cannot save renewal response", error);
        }
        return response;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${devpilot.servers.creation-secret-cleanup:1h}")
    public void clearExpiredCreationSecrets() {
        jdbc.update("UPDATE server_creation_request SET response_cipher=NULL WHERE expires_at<=? AND response_cipher IS NOT NULL", LocalDateTime.now(ZoneOffset.UTC));
        jdbc.update("UPDATE agent_token_renewal_request SET response_cipher=NULL WHERE expires_at<=? AND response_cipher IS NOT NULL", LocalDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public void delete(Long id) {
        ServerNodeEntity node = serverNodeMapper.lockActiveById(id);
        if (node == null) {
            throw BusinessException.notFound(40401, "服务器不存在");
        }
        if (serviceInstallationMapper.countInProgressByServer(id) > 0) {
            throw BusinessException.conflict(40967, "服务器仍有进行中的服务模板安装，请等待任务结束");
        }
        agentTokenMapper.revokeByServer(id, LocalDateTime.now(ZoneOffset.UTC));
        // Keep the idempotency tombstone, but remove recoverable credentials immediately.
        jdbc.update("UPDATE server_creation_request SET response_cipher=NULL WHERE server_id=?", id);
        jdbc.update("UPDATE agent_token_renewal_request SET response_cipher=NULL WHERE server_id=?", id);
        serverNodeMapper.deleteById(id);
    }

    private static String generateToken() {
        byte[] random = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(random);
        return "dp_agent_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String withoutTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

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

        String publicUrl = withoutTrailingSlash(agentProperties.publicUrl());
        String installCommand = "curl -fsSL " + shellQuote(publicUrl + "/install-agent.sh")
                + " | bash -s -- --server " + shellQuote(publicUrl)
                + " --token " + shellQuote(rawToken);
        return new CreateServerResponse(ServerNodeResponse.from(node), rawToken, installCommand);
    }

    @Transactional
    public void delete(Long id) {
        ServerNodeEntity node = serverNodeMapper.selectActiveById(id);
        if (node == null) {
            throw BusinessException.notFound(40401, "服务器不存在");
        }
        agentTokenMapper.revokeByServer(id, LocalDateTime.now(ZoneOffset.UTC));
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

package com.devpilot.server.publicapi.service;

import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.publicapi.dto.ApiTokenResponse;
import com.devpilot.server.publicapi.dto.CreateApiTokenRequest;
import com.devpilot.server.publicapi.dto.CreatedApiTokenResponse;
import com.devpilot.server.publicapi.entity.ApiAccessTokenEntity;
import com.devpilot.server.publicapi.mapper.ApiAccessTokenMapper;
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
public class ApiAccessTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private final ApiAccessTokenMapper mapper;

    public List<ApiTokenResponse> list() {
        return mapper.selectAll().stream().map(ApiAccessTokenService::response).toList();
    }

    @Transactional
    public CreatedApiTokenResponse create(CreateApiTokenRequest request, DevPilotPrincipal principal) {
        LocalDateTime now = now();
        if (request.expiresAt() != null && request.expiresAt().isAfter(now.plusYears(1))) {
            throw BusinessException.badRequest(40071, "API Token 最长有效期为一年");
        }
        String secret = generate();
        ApiAccessTokenEntity entity = new ApiAccessTokenEntity();
        entity.setName(request.name().trim());
        entity.setTokenPrefix(secret.substring(0, 16));
        entity.setTokenHash(SecretHashing.sha256(secret));
        entity.setScope("READ");
        entity.setStatus("ACTIVE");
        entity.setExpiresAt(request.expiresAt());
        entity.setCreatedBy(principal.userId());
        entity.setCreatedAt(now);
        mapper.insert(entity);
        return new CreatedApiTokenResponse(response(entity), secret);
    }

    @Transactional
    public void revoke(Long id) {
        if (mapper.selectById(id) == null) throw BusinessException.notFound(40471, "API Token 不存在");
        mapper.revoke(id, now());
    }

    @Transactional
    public ApiAccessTokenEntity authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith("dpat_") || rawToken.length() < 40) return null;
        LocalDateTime now = now();
        ApiAccessTokenEntity entity = mapper.selectValid(SecretHashing.sha256(rawToken), now);
        if (entity != null) mapper.touch(entity.getId(), now);
        return entity;
    }

    private static ApiTokenResponse response(ApiAccessTokenEntity value) {
        String status = value.getStatus();
        if ("ACTIVE".equals(status) && value.getExpiresAt() != null && !value.getExpiresAt().isAfter(now())) {
            status = "EXPIRED";
        }
        return new ApiTokenResponse(value.getId(), value.getName(), value.getTokenPrefix(), value.getScope(),
                status, value.getExpiresAt(), value.getLastUsedAt(), value.getRevokedAt(), value.getCreatedAt());
    }

    private static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return "dpat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}

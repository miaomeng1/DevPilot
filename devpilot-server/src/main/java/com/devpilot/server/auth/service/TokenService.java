package com.devpilot.server.auth.service;

import com.devpilot.server.auth.dto.AuthTokensResponse;
import com.devpilot.server.auth.dto.AuthUserResponse;
import com.devpilot.server.auth.entity.RefreshTokenEntity;
import com.devpilot.server.auth.mapper.RefreshTokenMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.DevPilotUserDetailsService;
import com.devpilot.server.security.JwtTokenProvider;
import com.devpilot.server.security.SecretHashing;
import com.devpilot.server.settings.service.SystemSettingsService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TokenService {

    private static final int REFRESH_TOKEN_BYTES = 64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenMapper refreshTokenMapper;
    private final DevPilotUserDetailsService userDetailsService;
    private final JwtTokenProvider jwtTokenProvider;
    private final SystemSettingsService settingsService;

    @Transactional
    public AuthenticatedSession createSession(DevPilotPrincipal principal, ClientMetadata metadata) {
        return createSession(principal, UUID.randomUUID().toString(), metadata);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthenticatedSession refreshSession(String rawRefreshToken, ClientMetadata metadata) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw BusinessException.unauthorized("刷新令牌缺失");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String currentHash = hash(rawRefreshToken);
        RefreshTokenEntity current = refreshTokenMapper.selectByHashForUpdate(currentHash);
        if (current == null) {
            throw BusinessException.unauthorized("刷新令牌无效");
        }
        if (current.getRevokedAt() != null) {
            refreshTokenMapper.revokeFamily(current.getFamilyId(), now);
            throw BusinessException.unauthorized("检测到已失效令牌被重复使用，请重新登录");
        }
        if (!current.getExpiresAt().isAfter(now)) {
            refreshTokenMapper.revokeFamily(current.getFamilyId(), now);
            throw BusinessException.unauthorized("登录会话已过期");
        }

        DevPilotPrincipal principal;
        try {
            principal = userDetailsService.loadById(current.getUserId());
        } catch (UsernameNotFoundException exception) {
            refreshTokenMapper.revokeFamily(current.getFamilyId(), now);
            throw BusinessException.unauthorized("用户不存在或已停用");
        }
        if (!principal.isEnabled()) {
            refreshTokenMapper.revokeFamily(current.getFamilyId(), now);
            throw BusinessException.unauthorized("用户不存在或已停用");
        }

        String nextRaw = randomToken();
        String nextHash = hash(nextRaw);
        if (refreshTokenMapper.rotate(current.getId(), nextHash, now) != 1) {
            refreshTokenMapper.revokeFamily(current.getFamilyId(), now);
            throw BusinessException.unauthorized("登录会话已在其他请求中刷新");
        }

        RefreshTokenEntity next = refreshEntity(principal.userId(), current.getFamilyId(), nextHash, metadata, now);
        refreshTokenMapper.insert(next);
        return authenticatedSession(principal, nextRaw);
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenMapper.revokeByHash(hash(rawRefreshToken), LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    public void revokeUserSessions(Long userId) {
        refreshTokenMapper.revokeByUser(userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    private AuthenticatedSession createSession(DevPilotPrincipal principal, String familyId, ClientMetadata metadata) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String rawToken = randomToken();
        RefreshTokenEntity refreshToken = refreshEntity(principal.userId(), familyId, hash(rawToken), metadata, now);
        refreshTokenMapper.insert(refreshToken);
        return authenticatedSession(principal, rawToken);
    }

    private AuthenticatedSession authenticatedSession(DevPilotPrincipal principal, String rawRefreshToken) {
        AuthTokensResponse response = new AuthTokensResponse(
                jwtTokenProvider.createAccessToken(principal),
                "Bearer",
                jwtTokenProvider.accessTokenTtlSeconds(),
                AuthUserResponse.from(principal));
        return new AuthenticatedSession(response, rawRefreshToken, settingsService.refreshTokenTtl());
    }

    private RefreshTokenEntity refreshEntity(Long userId, String familyId, String tokenHash,
                                             ClientMetadata metadata, LocalDateTime now) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(tokenHash);
        entity.setFamilyId(familyId);
        entity.setExpiresAt(now.plus(settingsService.refreshTokenTtl()));
        entity.setUserAgent(truncate(metadata.userAgent(), 512));
        entity.setIpAddress(truncate(metadata.ipAddress(), 64));
        entity.setCreatedAt(now);
        return entity;
    }

    private static String randomToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        return SecretHashing.sha256(token);
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}

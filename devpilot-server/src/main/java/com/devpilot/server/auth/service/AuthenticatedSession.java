package com.devpilot.server.auth.service;

import com.devpilot.server.auth.dto.AuthTokensResponse;
import java.time.Duration;

public record AuthenticatedSession(
        AuthTokensResponse response,
        String refreshToken,
        Duration refreshTokenTtl) {
}


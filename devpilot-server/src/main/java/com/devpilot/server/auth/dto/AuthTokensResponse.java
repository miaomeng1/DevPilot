package com.devpilot.server.auth.dto;

public record AuthTokensResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        AuthUserResponse user) {
}


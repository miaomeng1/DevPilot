package com.devpilot.server.publicapi.dto;

public record CreatedApiTokenResponse(ApiTokenResponse token, String oneTimeSecret) {
}

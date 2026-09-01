package com.devpilot.server.auth.dto;

import com.devpilot.server.security.DevPilotPrincipal;
import java.util.List;

public record AuthUserResponse(Long id, String username, String displayName, List<String> roles) {

    public static AuthUserResponse from(DevPilotPrincipal principal) {
        return new AuthUserResponse(principal.userId(), principal.getUsername(), principal.displayName(),
                principal.roles());
    }
}


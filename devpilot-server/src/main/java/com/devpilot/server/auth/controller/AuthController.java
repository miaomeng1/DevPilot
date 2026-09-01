package com.devpilot.server.auth.controller;

import com.devpilot.server.auth.dto.AuthTokensResponse;
import com.devpilot.server.auth.dto.AuthUserResponse;
import com.devpilot.server.auth.dto.ChangePasswordRequest;
import com.devpilot.server.auth.dto.LoginRequest;
import com.devpilot.server.auth.dto.SetupAdminRequest;
import com.devpilot.server.auth.dto.SetupStatusResponse;
import com.devpilot.server.auth.service.AuthService;
import com.devpilot.server.auth.service.AuthenticatedSession;
import com.devpilot.server.auth.service.ClientMetadata;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SecurityProperties securityProperties;

    @GetMapping("/setup/status")
    public ApiResponse<SetupStatusResponse> setupStatus() {
        return ApiResponse.success(authService.setupStatus());
    }

    @PostMapping("/setup")
    public ResponseEntity<ApiResponse<AuthTokensResponse>> setup(@Valid @RequestBody SetupAdminRequest request,
                                                                 HttpServletRequest httpRequest) {
        return sessionResponse(authService.setupAdministrator(request, metadata(httpRequest)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthTokensResponse>> login(@Valid @RequestBody LoginRequest request,
                                                                 HttpServletRequest httpRequest) {
        return sessionResponse(authService.login(request, metadata(httpRequest)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthTokensResponse>> refresh(
            @CookieValue(name = "${devpilot.security.refresh-cookie-name}", required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        return sessionResponse(authService.refresh(refreshToken, metadata(httpRequest)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "${devpilot.security.refresh-cookie-name}", required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(null));
    }

    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal DevPilotPrincipal principal) {
        authService.changePassword(principal, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(null));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(@AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(authService.currentUser(principal));
    }

    private ResponseEntity<ApiResponse<AuthTokensResponse>> sessionResponse(AuthenticatedSession session) {
        ResponseCookie cookie = ResponseCookie.from(securityProperties.refreshCookieName(), session.refreshToken())
                .httpOnly(true)
                .secure(securityProperties.refreshCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(session.refreshTokenTtl())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(session.response()));
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from(securityProperties.refreshCookieName(), "")
                .httpOnly(true)
                .secure(securityProperties.refreshCookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();
    }

    private static ClientMetadata metadata(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String address = forwardedFor == null || forwardedFor.isBlank()
                ? request.getRemoteAddr()
                : forwardedFor.split(",", 2)[0].trim();
        return new ClientMetadata(address, request.getHeader("User-Agent"));
    }
}

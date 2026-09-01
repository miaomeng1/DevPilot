package com.devpilot.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTests {

    private static final String PASSWORD = "DevPilot-Admin-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM alert_notification");
        jdbcTemplate.update("DELETE FROM alert_condition_state");
        jdbcTemplate.update("DELETE FROM alert_event");
        jdbcTemplate.update("DELETE FROM alert_rule");
        jdbcTemplate.update("DELETE FROM system_setting");
        jdbcTemplate.update("DELETE FROM nginx_config_history");
        jdbcTemplate.update("DELETE FROM nginx_command");
        jdbcTemplate.update("DELETE FROM nginx_config");
        jdbcTemplate.update("DELETE FROM nginx_host_snapshot");
        jdbcTemplate.update("DELETE FROM application_deployment");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM docker_command");
        jdbcTemplate.update("DELETE FROM docker_container_snapshot");
        jdbcTemplate.update("DELETE FROM docker_host_snapshot");
        jdbcTemplate.update("DELETE FROM server_metric");
        jdbcTemplate.update("DELETE FROM agent_token");
        jdbcTemplate.update("DELETE FROM server_node");
        jdbcTemplate.update("DELETE FROM auth_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    @Test
    void administratorSetupIssuesCookieAndBearerAccess() throws Exception {
        mockMvc.perform(get("/api/auth/setup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.setupRequired", is(true)));

        MvcResult setup = setupAdministrator();
        String accessToken = readAccessToken(setup);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username", is("admin")))
                .andExpect(jsonPath("$.data.roles", hasItem("ADMIN")));

        mockMvc.perform(get("/api/auth/setup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.setupRequired", is(false)));
    }

    @Test
    void refreshTokensRotateAndReuseRevokesTheFamily() throws Exception {
        String firstRefreshToken = readRefreshToken(setupAdministrator());

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("dp_refresh_token", firstRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andReturn();
        String secondRefreshToken = readRefreshToken(refreshed);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("dp_refresh_token", firstRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(40101)));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("dp_refresh_token", secondRefreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsBadCredentialsWithoutUserEnumeration() throws Exception {
        setupAdministrator();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("用户名或密码错误")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone-else","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("用户名或密码错误")));
    }

    @Test
    void repeatedLoginFailuresLockTheAccountAndSuccessfulLoginClearsExpiredState() throws Exception {
        setupAdministrator();

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"ADMIN","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message", is("用户名或密码错误")));
        }

        Integer failures = jdbcTemplate.queryForObject(
                "SELECT failed_login_count FROM sys_user WHERE username = 'admin'", Integer.class);
        LocalDateTime lockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM sys_user WHERE username = 'admin'", LocalDateTime.class);
        if (failures == null || failures != 5 || lockedUntil == null
                || !lockedUntil.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new AssertionError("Expected five failed attempts to lock the account");
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("用户名或密码错误")));

        jdbcTemplate.update("UPDATE sys_user SET locked_until = ? WHERE username = 'admin'",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk());

        Integer clearedFailures = jdbcTemplate.queryForObject(
                "SELECT failed_login_count FROM sys_user WHERE username = 'admin'", Integer.class);
        String clearedLock = jdbcTemplate.queryForObject(
                "SELECT CAST(locked_until AS VARCHAR) FROM sys_user WHERE username = 'admin'", String.class);
        if (clearedFailures == null || clearedFailures != 0 || clearedLock != null) {
            throw new AssertionError("Expected a successful login after expiry to clear lock state");
        }
    }

    @Test
    void userCanChangeOwnPasswordAndEveryExistingSessionIsRevoked() throws Exception {
        MvcResult setup = setupAdministrator();
        String accessToken = readAccessToken(setup);
        String refreshToken = readRefreshToken(setup);

        mockMvc.perform(put("/api/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"DevPilot-Changed-2026",
                                 "confirmPassword":"DevPilot-Changed-2026"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("dp_refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"DevPilot-Changed-2026"}
                                """))
                .andExpect(status().isOk());

        String auditParams = jdbcTemplate.queryForObject(
                "SELECT request_params FROM audit_log WHERE action = 'CHANGE_PASSWORD' ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        if (auditParams == null || !auditParams.contains("[REDACTED]")
                || auditParams.contains("DevPilot-Changed-2026")) {
            throw new AssertionError("Expected self-service password change audit fields to be redacted");
        }
    }

    private MvcResult setupAdministrator() throws Exception {
        return mockMvc.perform(post("/api/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("""
                                {
                                  "username": "Admin",
                                  "password": "%s",
                                  "confirmPassword": "%s",
                                  "displayName": "Platform Administrator",
                                  "email": "admin@example.com"
                                }
                                """.formatted(PASSWORD, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(jsonPath("$.data.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.data.user.roles", hasItem("ADMIN")))
                .andReturn();
    }

    private String readAccessToken(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
    }

    private String readRefreshToken(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        if (header == null) {
            throw new AssertionError("Set-Cookie header was not returned");
        }
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }
}

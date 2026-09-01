package com.devpilot.server.auth.service;

import com.devpilot.server.auth.dto.AuthUserResponse;
import com.devpilot.server.auth.dto.ChangePasswordRequest;
import com.devpilot.server.auth.dto.LoginRequest;
import com.devpilot.server.auth.dto.SetupAdminRequest;
import com.devpilot.server.auth.dto.SetupStatusResponse;
import com.devpilot.server.auth.entity.RoleEntity;
import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.entity.UserRoleEntity;
import com.devpilot.server.auth.mapper.RoleMapper;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.auth.mapper.UserRoleMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final LoginAttemptService loginAttemptService;

    public SetupStatusResponse setupStatus() {
        return new SetupStatusResponse(userMapper.countActiveUsers() == 0);
    }

    @Transactional
    public synchronized AuthenticatedSession setupAdministrator(SetupAdminRequest request, ClientMetadata metadata) {
        if (userMapper.countActiveUsers() != 0) {
            throw BusinessException.conflict(40901, "系统已经完成初始化");
        }
        if (!request.password().equals(request.confirmPassword())) {
            throw BusinessException.badRequest(40002, "两次输入的密码不一致");
        }

        RoleEntity adminRole = roleMapper.selectByCode("ADMIN");
        if (adminRole == null) {
            throw new BusinessException(50001, "系统角色尚未初始化", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserEntity user = new UserEntity();
        user.setUsername(normalizeUsername(request.username()));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setEmail(blankToNull(request.email()));
        user.setStatus("ACTIVE");
        user.setFailedLoginCount(0);
        user.setSessionVersion(0L);
        user.setDeleted(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        userRoleMapper.insert(new UserRoleEntity(user.getId(), adminRole.getId(), now));

        DevPilotPrincipal principal = new DevPilotPrincipal(user.getId(), user.getUsername(),
                user.getPasswordHash(), user.getDisplayName(), List.of("ADMIN"), true, true, 0L);
        return tokenService.createSession(principal, metadata);
    }

    public AuthenticatedSession login(LoginRequest request, ClientMetadata metadata) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizeUsername(request.username()), request.password()));
            DevPilotPrincipal principal = (DevPilotPrincipal) authentication.getPrincipal();
            userMapper.recordSuccessfulLogin(principal.userId(), LocalDateTime.now(ZoneOffset.UTC));
            return tokenService.createSession(principal, metadata);
        } catch (BadCredentialsException exception) {
            loginAttemptService.recordFailure(normalizeUsername(request.username()));
            throw BusinessException.unauthorized("用户名或密码错误");
        } catch (LockedException exception) {
            throw BusinessException.unauthorized("用户名或密码错误");
        } catch (AuthenticationException exception) {
            throw BusinessException.unauthorized("账户不可用");
        }
    }

    public AuthenticatedSession refresh(String refreshToken, ClientMetadata metadata) {
        return tokenService.refreshSession(refreshToken, metadata);
    }

    public void logout(String refreshToken) {
        tokenService.revoke(refreshToken);
    }

    @Transactional
    public void changePassword(DevPilotPrincipal principal, ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw BusinessException.badRequest(40002, "两次输入的新密码不一致");
        }
        UserEntity user = userMapper.selectActiveById(principal.userId());
        if (user == null || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw BusinessException.badRequest(40003, "当前密码错误");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw BusinessException.badRequest(40004, "新密码不能与当前密码相同");
        }
        userMapper.updatePasswordAndClearLock(user.getId(), passwordEncoder.encode(request.newPassword()),
                LocalDateTime.now(ZoneOffset.UTC));
        tokenService.revokeUserSessions(user.getId());
    }

    public AuthUserResponse currentUser(DevPilotPrincipal principal) {
        return AuthUserResponse.from(principal);
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

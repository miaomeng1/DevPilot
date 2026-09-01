package com.devpilot.server.user.service;

import com.devpilot.server.auth.entity.RoleEntity;
import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.entity.UserRoleEntity;
import com.devpilot.server.auth.mapper.RefreshTokenMapper;
import com.devpilot.server.auth.mapper.RoleMapper;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.auth.mapper.UserRoleMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.user.dto.CreateUserRequest;
import com.devpilot.server.user.dto.ResetPasswordRequest;
import com.devpilot.server.user.dto.UpdateUserRequest;
import com.devpilot.server.user.dto.UserResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAdministrationService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;

    public List<UserResponse> list() {
        return userMapper.selectAllUsers().stream().map(this::toResponse).toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw BusinessException.badRequest(40040, "两次输入的密码不一致");
        }
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (userMapper.selectAnyByUsername(username) != null) {
            throw BusinessException.conflict(40940, "用户名已存在");
        }
        String email = blankToNull(request.email());
        if (email != null && userMapper.selectAnyByEmail(email) != null) {
            throw BusinessException.conflict(40941, "邮箱已被使用");
        }
        RoleEntity role = requireRole(request.role());
        LocalDateTime now = now();
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setEmail(email);
        user.setStatus("ACTIVE");
        user.setFailedLoginCount(0);
        user.setSessionVersion(0L);
        user.setDeleted(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        userRoleMapper.insert(new UserRoleEntity(user.getId(), role.getId(), now));
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request, DevPilotPrincipal principal) {
        UserEntity user = require(id);
        String currentRole = role(user.getId());
        if (principal.userId().equals(id)
                && (!currentRole.equals(request.role()) || !"ACTIVE".equals(request.status()))) {
            throw BusinessException.badRequest(40041, "不能更改自己的角色或停用自己的账户");
        }
        if ("ADMIN".equals(currentRole) && "ACTIVE".equals(user.getStatus())
                && (!"ADMIN".equals(request.role()) || !"ACTIVE".equals(request.status()))
                && userMapper.countActiveAdministrators() <= 1) {
            throw BusinessException.conflict(40942, "系统必须保留至少一个启用的管理员");
        }
        String email = blankToNull(request.email());
        UserEntity sameEmail = email == null ? null : userMapper.selectActiveByEmail(email);
        if (sameEmail != null && !sameEmail.getId().equals(id)) {
            throw BusinessException.conflict(40941, "邮箱已被使用");
        }
        RoleEntity newRole = requireRole(request.role());
        user.setDisplayName(request.displayName().trim());
        user.setEmail(email);
        boolean securityChanged = !currentRole.equals(request.role()) || !user.getStatus().equals(request.status());
        user.setStatus(request.status());
        if (securityChanged) {
            user.setSessionVersion(user.getSessionVersion() + 1);
        }
        user.setUpdatedAt(now());
        userMapper.updateById(user);
        if (!currentRole.equals(request.role())) {
            userRoleMapper.deleteByUser(id);
            userRoleMapper.insert(new UserRoleEntity(id, newRole.getId(), now()));
        }
        if (!"ACTIVE".equals(request.status()) || !currentRole.equals(request.role())) {
            refreshTokenMapper.revokeByUser(id, now());
        }
        return toResponse(user);
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw BusinessException.badRequest(40040, "两次输入的密码不一致");
        }
        UserEntity user = require(id);
        userMapper.updatePasswordAndClearLock(id, passwordEncoder.encode(request.password()), now());
        refreshTokenMapper.revokeByUser(id, now());
    }

    @Transactional
    public void delete(Long id, DevPilotPrincipal principal) {
        UserEntity user = require(id);
        if (principal.userId().equals(id)) {
            throw BusinessException.badRequest(40041, "不能删除自己的账户");
        }
        if ("ADMIN".equals(role(id)) && "ACTIVE".equals(user.getStatus())
                && userMapper.countActiveAdministrators() <= 1) {
            throw BusinessException.conflict(40942, "系统必须保留至少一个启用的管理员");
        }
        refreshTokenMapper.revokeByUser(id, now());
        userRoleMapper.deleteByUser(id);
        userMapper.deleteById(id);
    }

    private UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getId().toString(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                role(user.getId()), user.getStatus(), user.getLastLoginAt(), user.getCreatedAt(), user.getUpdatedAt());
    }

    private UserEntity require(Long id) {
        UserEntity user = userMapper.selectActiveById(id);
        if (user == null) throw BusinessException.notFound(40440, "用户不存在");
        return user;
    }

    private RoleEntity requireRole(String code) {
        RoleEntity role = roleMapper.selectByCode(code);
        if (role == null) throw BusinessException.badRequest(40042, "角色不存在");
        return role;
    }

    private String role(Long userId) {
        List<String> roles = userRoleMapper.selectRoleCodes(userId);
        return roles.isEmpty() ? "VIEWER" : roles.getFirst();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}

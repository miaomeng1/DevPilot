package com.devpilot.server.security;

import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.mapper.UserMapper;
import com.devpilot.server.auth.mapper.UserRoleMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DevPilotUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity user = userMapper.selectActiveByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return toPrincipal(user);
    }

    public DevPilotPrincipal loadById(Long userId) {
        UserEntity user = userMapper.selectActiveById(userId);
        if (user == null) {
            throw new UsernameNotFoundException("User does not exist");
        }
        return toPrincipal(user);
    }

    private DevPilotPrincipal toPrincipal(UserEntity user) {
        List<String> roles = userRoleMapper.selectRoleCodes(user.getId());
        boolean enabled = "ACTIVE".equals(user.getStatus());
        boolean accountNonLocked = user.getLockedUntil() == null
                || !user.getLockedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC));
        return new DevPilotPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getDisplayName(), roles, enabled, accountNonLocked,
                user.getSessionVersion() == null ? 0 : user.getSessionVersion());
    }
}

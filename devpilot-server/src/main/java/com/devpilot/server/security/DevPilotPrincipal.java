package com.devpilot.server.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class DevPilotPrincipal implements UserDetails {

    private final Long userId;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final List<String> roles;
    private final boolean enabled;
    private final boolean accountNonLocked;
    private final long sessionVersion;

    public DevPilotPrincipal(Long userId, String username, String passwordHash, String displayName,
                             List<String> roles, boolean enabled, boolean accountNonLocked, long sessionVersion) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.roles = List.copyOf(roles);
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
        this.sessionVersion = sessionVersion;
    }

    public Long userId() {
        return userId;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> roles() {
        return roles;
    }

    public long sessionVersion() {
        return sessionVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

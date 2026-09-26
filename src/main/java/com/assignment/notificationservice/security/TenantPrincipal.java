package com.assignment.notificationservice.security;

import com.assignment.notificationservice.constants.SecurityConstants;
import com.assignment.notificationservice.models.enums.AuthMethod;
import com.assignment.notificationservice.models.enums.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The single principal type for both auth paths, so {@link CurrentTenant} resolves the
 * tenant the same way whether the caller used HTTP Basic or an API key.
 *
 * <p>{@code tenantId} is null for PLATFORM_ADMIN. {@code passwordHash} is only present on
 * the Basic path, where Spring's DaoAuthenticationProvider checks it; API key principals
 * are already authenticated when built.
 */
public class TenantPrincipal implements UserDetails {

    @Getter
    private final UUID tenantId;
    private final String username;
    @Getter
    private final Role role;
    @Getter
    private final AuthMethod authMethod;
    private final String passwordHash;

    public TenantPrincipal(UUID tenantId, String username, Role role, AuthMethod authMethod) {
        this(tenantId, username, role, authMethod, null);
    }

    public TenantPrincipal(UUID tenantId, String username, Role role, AuthMethod authMethod,
                           String passwordHash) {
        this.tenantId = tenantId;
        this.username = username;
        this.role = role;
        this.authMethod = authMethod;
        this.passwordHash = passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(SecurityConstants.AUTHORITY_ROLE_PREFIX + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username != null ? username : "api-key:" + tenantId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

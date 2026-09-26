package com.assignment.notificationservice.services;

import com.assignment.notificationservice.models.AppUser;
import com.assignment.notificationservice.models.enums.AuthMethod;
import com.assignment.notificationservice.repositories.AppUserRepository;
import com.assignment.notificationservice.security.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads console users for HTTP Basic. The returned principal carries the BCrypt hash. */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return new TenantPrincipal(
                user.getTenant() != null ? user.getTenant().getId() : null,
                user.getUsername(),
                user.getRole(),
                AuthMethod.BASIC,
                user.getPasswordHash());
    }
}

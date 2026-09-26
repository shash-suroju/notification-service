package com.assignment.notificationservice.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * The only source of the caller's tenant ID. Controllers never read it from the path or
 * body, so a tenant admin cannot address another tenant's data by editing a URL.
 */
public final class CurrentTenant {

    private CurrentTenant() {
        // static utility
    }

    /**
     * @throws AccessDeniedException if the caller is unauthenticated or has no tenant
     *                               (e.g. a platform admin calling a tenant API)
     */
    public static UUID resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TenantPrincipal principal)) {
            throw new AccessDeniedException("No authenticated tenant");
        }
        UUID tenantId = principal.getTenantId();
        if (tenantId == null) {
            throw new AccessDeniedException("Platform admin cannot access tenant-scoped resources");
        }
        return tenantId;
    }
}

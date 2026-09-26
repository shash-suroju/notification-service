package com.assignment.notificationservice.security;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.constants.SecurityConstants;
import com.assignment.notificationservice.dtos.ApiKeyAuthentication;
import com.assignment.notificationservice.models.enums.AuthMethod;
import com.assignment.notificationservice.models.enums.Role;
import com.assignment.notificationservice.services.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates {@code X-API-Key} on the send API. On failure it leaves the context empty
 * and lets the authorization rules answer 401, so bad keys and missing keys look identical.
 *
 * <p>Only runs on {@code /api/v1/notifications/**}: API keys cannot reach the tenant admin
 * console even though the principal carries the TENANT_ADMIN role.
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader(SecurityConstants.API_KEY_HEADER);

        if (apiKey != null && !apiKey.isBlank()) {
            Optional<ApiKeyAuthentication> auth = apiKeyService.authenticate(apiKey.trim());
            if (auth.isPresent()) {
                TenantPrincipal principal = new TenantPrincipal(
                        auth.get().tenantId(), null, Role.TENANT_ADMIN, AuthMethod.API_KEY);
                UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(token);
                SecurityContextHolder.setContext(context);
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ApiPaths.NOTIFICATIONS);
    }
}

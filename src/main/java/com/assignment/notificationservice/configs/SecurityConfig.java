package com.assignment.notificationservice.configs;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.constants.SecurityConstants;
import com.assignment.notificationservice.security.ApiKeyAuthFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Three audiences, three scopes:
 * <ul>
 *   <li>{@code /api/v1/admin/**} — PLATFORM_ADMIN via HTTP Basic</li>
 *   <li>{@code /api/v1/tenant/**} — TENANT_ADMIN via HTTP Basic</li>
 *   <li>{@code /api/v1/notifications/**} — tenant backends via {@code X-API-Key}</li>
 * </ul>
 * Unauthenticated → 401; authenticated with the wrong role → 403.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Stateless requests arrive at /error anonymous; without this a 403
                        // is re-challenged on the error dispatch and surfaces as 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(ApiPaths.ADMIN + ApiPaths.ALL_BELOW)
                            .hasRole(SecurityConstants.ROLE_PLATFORM_ADMIN)
                        .requestMatchers(ApiPaths.TENANT + ApiPaths.ALL_BELOW)
                            .hasRole(SecurityConstants.ROLE_TENANT_ADMIN)
                        .requestMatchers(ApiPaths.NOTIFICATIONS + ApiPaths.ALL_BELOW).authenticated()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    /**
     * ApiKeyAuthFilter is a {@code @Component}, so Boot would also register it as a plain
     * servlet filter outside the security chain. It must only run inside the chain.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}

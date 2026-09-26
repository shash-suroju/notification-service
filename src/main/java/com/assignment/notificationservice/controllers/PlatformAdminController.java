package com.assignment.notificationservice.controllers;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.constants.PaginationConstants;
import com.assignment.notificationservice.dtos.CreateTenantAdminRequest;
import com.assignment.notificationservice.dtos.CreateTenantRequest;
import com.assignment.notificationservice.dtos.GlobalChannelLimitResponse;
import com.assignment.notificationservice.dtos.PageResponse;
import com.assignment.notificationservice.dtos.PlatformSettingsResponse;
import com.assignment.notificationservice.dtos.TenantAdminResponse;
import com.assignment.notificationservice.dtos.TenantResponse;
import com.assignment.notificationservice.dtos.UpdateGlobalLimitRequest;
import com.assignment.notificationservice.dtos.UpdatePlatformSettingsRequest;
import com.assignment.notificationservice.dtos.UpdateTenantRequest;
import com.assignment.notificationservice.models.AppUser;
import com.assignment.notificationservice.models.GlobalChannelLimit;
import com.assignment.notificationservice.models.Tenant;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.GlobalLimitService;
import com.assignment.notificationservice.services.PlatformSettingsService;
import com.assignment.notificationservice.services.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform-admin console (PLATFORM_ADMIN via HTTP Basic, enforced in SecurityConfig).
 * Unlike the tenant console, tenant IDs are taken from the path here: the platform admin
 * legitimately operates on every tenant.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN)
@RequiredArgsConstructor
public class PlatformAdminController {

    private final TenantService tenantService;
    private final GlobalLimitService globalLimitService;
    private final PlatformSettingsService settingsService;

    // ──── Tenants ────

    @PostMapping(ApiPaths.ADMIN_TENANTS)
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(tenantService.create(request)));
    }

    /** Newest first. */
    @GetMapping(ApiPaths.ADMIN_TENANTS)
    public PageResponse<TenantResponse> listTenants(
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE_SIZE) int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, PaginationConstants.MAX_PAGE_SIZE));
        return PageResponse.from(tenantService.listAll(pageable).map(this::toResponse));
    }

    @GetMapping(ApiPaths.ADMIN_TENANTS + "/{id}")
    public TenantResponse getTenant(@PathVariable UUID id) {
        return toResponse(tenantService.getById(id));
    }

    @PatchMapping(ApiPaths.ADMIN_TENANTS + "/{id}")
    public TenantResponse updateTenant(@PathVariable UUID id, @Valid @RequestBody UpdateTenantRequest request) {
        return toResponse(tenantService.update(id, request));
    }

    // ──── Tenant lifecycle ────

    @PostMapping(ApiPaths.ADMIN_TENANTS + "/{id}/suspend")
    public TenantResponse suspendTenant(@PathVariable UUID id) {
        return toResponse(tenantService.suspend(id));
    }

    @PostMapping(ApiPaths.ADMIN_TENANTS + "/{id}/activate")
    public TenantResponse activateTenant(@PathVariable UUID id) {
        return toResponse(tenantService.activate(id));
    }

    // ──── Tenant admin users ────

    @PostMapping(ApiPaths.ADMIN_TENANTS + "/{id}/admins")
    public ResponseEntity<TenantAdminResponse> createTenantAdmin(
            @PathVariable UUID id, @Valid @RequestBody CreateTenantAdminRequest request) {
        AppUser user = tenantService.createTenantAdmin(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toAdminResponse(user, id));
    }

    @GetMapping(ApiPaths.ADMIN_TENANTS + "/{id}/admins")
    public List<TenantAdminResponse> listTenantAdmins(@PathVariable UUID id) {
        return tenantService.listTenantAdmins(id).stream().map(u -> toAdminResponse(u, id)).toList();
    }

    // ──── Global channel limits ────

    @GetMapping(ApiPaths.ADMIN_GLOBAL_LIMITS)
    public List<GlobalChannelLimitResponse> listGlobalLimits() {
        return globalLimitService.listAll().stream().map(this::toLimitResponse).toList();
    }

    @PutMapping(ApiPaths.ADMIN_GLOBAL_LIMITS + "/{channel}")
    public GlobalChannelLimitResponse updateGlobalLimit(@PathVariable String channel,
                                                        @Valid @RequestBody UpdateGlobalLimitRequest request) {
        return toLimitResponse(globalLimitService.update(Channel.fromPath(channel), request));
    }

    // ──── Platform settings ────

    @GetMapping(ApiPaths.ADMIN_SETTINGS)
    public PlatformSettingsResponse getSettings() {
        return settingsService.getSettings();
    }

    @PutMapping(ApiPaths.ADMIN_SETTINGS)
    public PlatformSettingsResponse updateSettings(@Valid @RequestBody UpdatePlatformSettingsRequest request) {
        return settingsService.updateSettings(request);
    }

    // ──── Mappers ────

    private TenantResponse toResponse(Tenant t) {
        return new TenantResponse(t.getId(), t.getName(), t.getSlug(), t.getStatus(),
                t.getRateLimitPerSec(), t.getBurst(), t.getWeight(), t.getMaxAttempts(),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    /** tenantId comes from the path, avoiding a touch of the lazy tenant association. */
    private TenantAdminResponse toAdminResponse(AppUser u, UUID tenantId) {
        return new TenantAdminResponse(u.getId(), u.getUsername(), u.getRole(), tenantId, u.getCreatedAt());
    }

    private GlobalChannelLimitResponse toLimitResponse(GlobalChannelLimit l) {
        return new GlobalChannelLimitResponse(l.getChannel(), l.getRatePerSec(), l.getBurst());
    }
}

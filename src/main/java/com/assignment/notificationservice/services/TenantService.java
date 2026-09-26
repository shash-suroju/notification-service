package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.PlatformSettingKeys;
import com.assignment.notificationservice.dtos.CreateTenantAdminRequest;
import com.assignment.notificationservice.dtos.CreateTenantRequest;
import com.assignment.notificationservice.dtos.UpdateTenantRequest;
import com.assignment.notificationservice.exceptions.ConflictException;
import com.assignment.notificationservice.exceptions.EntityNotFoundException;
import com.assignment.notificationservice.models.AppUser;
import com.assignment.notificationservice.models.ChannelConfig;
import com.assignment.notificationservice.models.Tenant;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.Role;
import com.assignment.notificationservice.models.enums.TenantStatus;
import com.assignment.notificationservice.repositories.AppUserRepository;
import com.assignment.notificationservice.repositories.ChannelConfigRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import com.assignment.notificationservice.utils.AfterCommit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Tenant lifecycle for the platform admin.
 *
 * <p>Invariants checked here (and backed by CHECK constraints in V001): burst ≥ rate,
 * rate ≤ the platform's {@code max_tenant_rate_per_sec}, weight 1–10, maxAttempts 1–20.
 * Concurrent edits to one tenant are caught by its {@code @Version} column and answered 409.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final ChannelConfigRepository channelConfigRepository;
    private final PlatformSettingsService platformSettingsService;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** Creates an ACTIVE tenant with all four channels configured and enabled. */
    @Transactional
    public Tenant create(CreateTenantRequest request) {
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new ConflictException("Tenant with slug '" + request.slug() + "' already exists");
        }
        if (tenantRepository.existsByName(request.name())) {
            throw new ConflictException("Tenant with name '" + request.name() + "' already exists");
        }
        validateRate(request.rateLimitPerSec());
        validateBurst(request.burst(), request.rateLimitPerSec());

        Tenant tenant = new Tenant(request.name(), request.slug(), clock.instant());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setRateLimitPerSec(request.rateLimitPerSec());
        tenant.setBurst(request.burst());
        tenant.setWeight(request.weight() != null ? request.weight() : PlatformSettingKeys.DEFAULT_TENANT_WEIGHT);
        tenant.setMaxAttempts(request.maxAttempts() != null
                ? request.maxAttempts()
                : platformSettingsService.getDefaultMaxAttempts());
        tenant = tenantRepository.save(tenant);

        Tenant saved = tenant;
        channelConfigRepository.saveAll(Arrays.stream(Channel.values())
                .map(channel -> new ChannelConfig(saved, channel, true))
                .toList());

        return tenant;
    }

    /** Partial update; null fields are left unchanged. New rate limits apply from the next dispatch. */
    @Transactional
    public Tenant update(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = getById(tenantId);

        if (request.name() != null && !request.name().equals(tenant.getName())) {
            if (tenantRepository.existsByName(request.name())) {
                throw new ConflictException("Tenant with name '" + request.name() + "' already exists");
            }
            tenant.setName(request.name());
        }
        if (request.rateLimitPerSec() != null) {
            validateRate(request.rateLimitPerSec());
            tenant.setRateLimitPerSec(request.rateLimitPerSec());
        }
        if (request.burst() != null) {
            tenant.setBurst(request.burst());
        }
        // Checked on the merged result: either side of the pair may have changed.
        validateBurst(tenant.getBurst(), tenant.getRateLimitPerSec());

        if (request.weight() != null) {
            tenant.setWeight(request.weight());
        }
        if (request.maxAttempts() != null) {
            // Applies to notifications accepted from now on; queued ones keep their copied budget.
            tenant.setMaxAttempts(request.maxAttempts());
        }
        tenant.setUpdatedAt(clock.instant());

        Tenant saved = tenantRepository.saveAndFlush(tenant);
        AfterCommit.run(() -> rateLimiterRegistry.refreshTenant(tenantId));
        return saved;
    }

    /**
     * Suspended tenants can still authenticate and read their data, but new submits are refused
     * (403) and their queued notifications are not dispatched until reactivated.
     */
    @Transactional
    public Tenant suspend(UUID tenantId) {
        Tenant tenant = getById(tenantId);
        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            throw new ConflictException("Tenant is already suspended");
        }
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenant.setUpdatedAt(clock.instant());
        return tenantRepository.saveAndFlush(tenant);
    }

    @Transactional
    public Tenant activate(UUID tenantId) {
        Tenant tenant = getById(tenantId);
        if (tenant.getStatus() == TenantStatus.ACTIVE) {
            throw new ConflictException("Tenant is already active");
        }
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setUpdatedAt(clock.instant());
        return tenantRepository.saveAndFlush(tenant);
    }

    @Transactional
    public AppUser createTenantAdmin(UUID tenantId, CreateTenantAdminRequest request) {
        Tenant tenant = getById(tenantId);
        if (appUserRepository.existsByUsername(request.username())) {
            throw new ConflictException("Username '" + request.username() + "' already exists");
        }
        AppUser user = new AppUser(request.username(), passwordEncoder.encode(request.password()),
                Role.TENANT_ADMIN, tenant, clock.instant());
        return appUserRepository.save(user);
    }

    public Tenant getById(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));
    }

    public Page<Tenant> listAll(Pageable pageable) {
        return tenantRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public List<AppUser> listTenantAdmins(UUID tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new EntityNotFoundException("Tenant", tenantId);
        }
        return appUserRepository.findByTenantIdAndRoleOrderByCreatedAtAsc(tenantId, Role.TENANT_ADMIN);
    }

    private void validateRate(int rateLimitPerSec) {
        int maxRate = platformSettingsService.getMaxTenantRatePerSec();
        if (rateLimitPerSec > maxRate) {
            throw new IllegalArgumentException(
                    "rateLimitPerSec (" + rateLimitPerSec + ") exceeds platform max (" + maxRate + ")");
        }
    }

    private static void validateBurst(int burst, int rateLimitPerSec) {
        if (burst < rateLimitPerSec) {
            throw new IllegalArgumentException("burst must be >= rateLimitPerSec");
        }
    }
}

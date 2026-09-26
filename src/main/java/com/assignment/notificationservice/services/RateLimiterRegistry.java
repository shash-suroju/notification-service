package com.assignment.notificationservice.services;

import com.assignment.notificationservice.models.Tenant;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.repositories.GlobalChannelLimitRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import com.assignment.notificationservice.utils.TokenBucket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily-built token buckets: one per tenant, one per channel (the global ceiling shared by
 * all tenants). Buckets are in memory, per dispatcher instance; limits are read from the
 * database when a bucket is first built, and {@code refresh*} drops a bucket so the next use
 * rebuilds it with the current limits.
 */
@Component
@RequiredArgsConstructor
public class RateLimiterRegistry {

    /** Used if a channel has no global_channel_limit row. */
    private static final int DEFAULT_CHANNEL_RATE = 500;
    private static final int DEFAULT_CHANNEL_BURST = 1000;

    private final ConcurrentHashMap<UUID, TokenBucket> tenantBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, TokenBucket> channelBuckets = new ConcurrentHashMap<>();
    private final TenantRepository tenantRepository;
    private final GlobalChannelLimitRepository globalChannelLimitRepository;
    private final Clock clock;

    public TokenBucket tenantBucket(UUID tenantId) {
        return tenantBuckets.computeIfAbsent(tenantId, id -> {
            Tenant tenant = tenantRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("Tenant not found: " + id));
            return new TokenBucket(tenant.getRateLimitPerSec(), tenant.getBurst(), clock);
        });
    }

    public TokenBucket channelBucket(Channel channel) {
        return channelBuckets.computeIfAbsent(channel, ch -> globalChannelLimitRepository.findById(ch)
                .map(limit -> new TokenBucket(limit.getRatePerSec(), limit.getBurst(), clock))
                .orElseGet(() -> new TokenBucket(DEFAULT_CHANNEL_RATE, DEFAULT_CHANNEL_BURST, clock)));
    }

    /** Call after an admin changes a tenant's rate limit. */
    public void refreshTenant(UUID tenantId) {
        tenantBuckets.remove(tenantId);
    }

    /** Call after an admin changes a global channel limit. */
    public void refreshChannel(Channel channel) {
        channelBuckets.remove(channel);
    }

    /** Drops every bucket; all are rebuilt (full) from current limits on next use. */
    public void resetAll() {
        tenantBuckets.clear();
        channelBuckets.clear();
    }
}

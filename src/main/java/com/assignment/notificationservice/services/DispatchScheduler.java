package com.assignment.notificationservice.services;

import com.assignment.notificationservice.configs.DispatcherProperties;
import com.assignment.notificationservice.dtos.ClaimedNotification;
import com.assignment.notificationservice.dtos.TenantWithWeight;
import com.assignment.notificationservice.dtos.TenantWork;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.utils.TokenBucket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dispatch loop. Each {@link #tick()}:
 * <ol>
 *   <li>reaps expired leases (at most once per {@code reaper-interval})</li>
 *   <li>finds tenants with due work and orders them fairly ({@link FairTenantSelector})</li>
 *   <li>per tenant × channel: sizes the batch by pool capacity, then by the tenant's and the
 *       channel's token buckets, claims that many rows, and hands them to the channel's pool</li>
 * </ol>
 *
 * <p>Tokens are taken <em>before</em> claiming, so every claimed row is already within both
 * rate limits; tokens not used by the claim are returned. Rate-limited work is simply left
 * queued for a later tick — deferred, never failed.
 *
 * <p>With {@code notify.dispatcher.auto-start=false} (the test profile) nothing runs on its
 * own and tests drive {@link #tick()} directly, one deterministic step at a time.
 */
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);

    private final WorkClaimer workClaimer;
    private final FairTenantSelector fairTenantSelector;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final ChannelSenderRegistry senderRegistry;
    private final OutcomeRecorder outcomeRecorder;
    private final LeaseReaper leaseReaper;
    private final ChannelWorkerPools pools;
    private final DispatcherProperties props;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private Instant lastReap;

    @PostConstruct
    void init() {
        if (props.isAutoStart()) {
            start();
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dispatch-scheduler");
                t.setDaemon(true);
                return t;
            });
            // Fixed delay, not fixed rate: a slow tick delays the next one instead of piling up.
            scheduler.scheduleWithFixedDelay(this::safeTick, 0, props.getTickIntervalMs(), TimeUnit.MILLISECONDS);
            log.info("Dispatcher started (tick every {} ms)", props.getTickIntervalMs());
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false) && scheduler != null) {
            scheduler.shutdown();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * One dispatch cycle. Synchronized so a manual tick can never overlap a scheduled one.
     *
     * @return number of notifications handed to worker pools in this tick
     */
    public synchronized int tick() {
        Instant now = clock.instant();
        maybeReap(now);

        List<TenantWithWeight> tenantsWithWork = workClaimer.findTenantsWithDueWork(now);
        if (tenantsWithWork.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        for (TenantWork work : fairTenantSelector.selectForTick(tenantsWithWork, props.getBaseQuantum())) {
            for (Channel channel : Channel.values()) {
                try {
                    dispatched += dispatch(work, channel);
                } catch (RuntimeException e) {
                    // One tenant's failure must not stop everyone else's delivery.
                    log.error("Dispatch failed for tenant {} channel {}", work.tenantId(), channel, e);
                }
            }
        }
        return dispatched;
    }

    private int dispatch(TenantWork work, Channel channel) {
        ThreadPoolExecutor pool = pools.get(channel);
        int wanted = Math.min(work.quantum(), pools.freeSlots(channel));
        if (wanted <= 0) {
            return 0;       // pool saturated: back-pressure, leave rows queued
        }

        // Layer 1: the tenant's own rate limit
        TokenBucket tenantBucket = rateLimiterRegistry.tenantBucket(work.tenantId());
        int tenantGranted = tenantBucket.tryAcquire(wanted);
        if (tenantGranted == 0) {
            return 0;
        }

        // Layer 2: the channel's global limit, shared by all tenants
        TokenBucket channelBucket = rateLimiterRegistry.channelBucket(channel);
        int granted = channelBucket.tryAcquire(tenantGranted);
        tenantBucket.release(tenantGranted - granted);
        if (granted == 0) {
            return 0;
        }

        List<ClaimedNotification> claimed = workClaimer.claim(
                work.tenantId(), channel, granted, workClaimer.newLockToken(), props.leaseDuration());

        int unused = granted - claimed.size();
        tenantBucket.release(unused);
        channelBucket.release(unused);

        int submitted = 0;
        ChannelSender sender = senderRegistry.get(channel);
        for (ClaimedNotification n : claimed) {
            try {
                // execute(), not submit(): submit() would bury any uncaught exception in a Future.
                pool.execute(new DeliveryWorker(n, sender, outcomeRecorder, clock));
                submitted++;
            } catch (RejectedExecutionException e) {
                // Capacity changed between freeSlots() and now. No attempt was made, so give
                // the row back without consuming an attempt, and refund its tokens.
                workClaimer.releaseToPending(n);
                tenantBucket.release(1);
                channelBucket.release(1);
            }
        }
        return submitted;
    }

    private void maybeReap(Instant now) {
        if (lastReap == null || !now.isBefore(lastReap.plus(props.reaperInterval()))) {
            leaseReaper.reap();
            lastReap = now;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            // Keep the scheduler thread alive: an exception escaping here would cancel all future ticks.
            log.error("Dispatch tick failed", e);
        }
    }

    @PreDestroy
    void shutdown() {
        stop();
    }
}

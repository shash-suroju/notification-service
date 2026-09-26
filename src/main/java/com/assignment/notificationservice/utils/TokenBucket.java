package com.assignment.notificationservice.utils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Token bucket: refills at {@code ratePerSec}, holds at most {@code burst} tokens.
 *
 * <p>{@link #tryAcquire} grants <em>up to</em> the requested amount rather than all-or-nothing,
 * so a tenant wanting 20 with 8 tokens left claims 8 rows instead of wasting the tick.
 * Tokens are fractional, so a 10/s bucket refills 5 tokens in 500 ms exactly. Time comes from
 * the injected {@link Clock}, which makes refill fully deterministic in tests.
 *
 * <p>Limits are immutable; to change them, replace the bucket (see {@code RateLimiterRegistry}).
 */
public class TokenBucket {

    private final double ratePerSec;
    private final int burst;
    private final Clock clock;
    private double tokens;
    private Instant lastRefill;

    public TokenBucket(int ratePerSec, int burst, Clock clock) {
        if (ratePerSec <= 0 || burst <= 0) {
            throw new IllegalArgumentException("ratePerSec and burst must be positive");
        }
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.clock = clock;
        this.tokens = burst;              // start full
        this.lastRefill = clock.instant();
    }

    /** Takes up to {@code requested} tokens and returns how many were granted. Never blocks. */
    public synchronized int tryAcquire(int requested) {
        if (requested <= 0) {
            return 0;
        }
        refill();
        int granted = (int) Math.min(requested, Math.floor(tokens));
        tokens -= granted;
        return granted;
    }

    /** Returns tokens that were acquired but not used, capped at {@code burst}. */
    public synchronized void release(int count) {
        if (count > 0) {
            tokens = Math.min(burst, tokens + count);
        }
    }

    /** Current whole tokens available (refills first). For diagnostics and tests. */
    public synchronized int available() {
        refill();
        return (int) Math.floor(tokens);
    }

    public int getBurst() {
        return burst;
    }

    public double getRatePerSec() {
        return ratePerSec;
    }

    private void refill() {
        Instant now = clock.instant();
        long elapsedNanos = Duration.between(lastRefill, now).toNanos();
        if (elapsedNanos > 0) {
            tokens = Math.min(burst, tokens + (elapsedNanos / 1_000_000_000.0) * ratePerSec);
            lastRefill = now;
        }
    }
}

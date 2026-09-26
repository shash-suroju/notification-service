package com.assignment.notificationservice.utils;

import java.time.Duration;
import java.util.Random;

/**
 * Retry delay: {@code min(maxDelay, baseDelay × 2^(attempt−1))}, then "equal jitter" —
 * half of that is guaranteed, the other half is random.
 *
 * <p>Equal jitter rather than full jitter: full jitter can pick ~0 ms, which gives a
 * struggling provider no breathing room. The guaranteed half keeps real back-pressure while
 * the random half still spreads retries out and prevents a thundering herd.
 */
public class ExponentialBackoffWithJitter {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final Random random;

    public ExponentialBackoffWithJitter(long baseDelayMs, long maxDelayMs) {
        this(baseDelayMs, maxDelayMs, new Random());
    }

    /** Seeded {@link Random} for deterministic tests. */
    public ExponentialBackoffWithJitter(long baseDelayMs, long maxDelayMs, Random random) {
        if (baseDelayMs <= 0 || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("require 0 < baseDelayMs <= maxDelayMs");
        }
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.random = random;
    }

    /**
     * @param attemptNo the attempt that just failed, 1-based
     * @return a delay in {@code [rawDelay/2, rawDelay]}, where rawDelay is capped at maxDelay
     */
    public Duration nextDelay(int attemptNo) {
        long cappedMs = rawDelayMs(attemptNo);
        long floor = cappedMs / 2;
        long jitter = (long) (random.nextDouble() * (cappedMs - floor + 1));
        return Duration.ofMillis(Math.min(cappedMs, floor + jitter));
    }

    /** The un-jittered, capped delay for an attempt. Overflow-safe for any attempt number. */
    public long rawDelayMs(int attemptNo) {
        int exponent = Math.max(0, attemptNo - 1);
        if (exponent >= 62) {                      // 2^62 × base overflows long
            return maxDelayMs;
        }
        long multiplier = 1L << exponent;
        if (baseDelayMs > maxDelayMs / multiplier) {
            return maxDelayMs;
        }
        return Math.min(maxDelayMs, baseDelayMs * multiplier);
    }
}

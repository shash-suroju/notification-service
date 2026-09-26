package com.assignment.notificationservice.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Clock whose instant can be set and advanced manually.
 *
 * <p>This is what makes time-dependent tests deterministic: instead of sleeping until a
 * backoff or lease actually expires, a test advances the clock and ticks the dispatcher.
 * Backed by an {@link AtomicReference} because worker threads read it concurrently.
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
    }

    public MutableClock() {
        this(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return this;
    }

    /** Advance time by the given duration. Returns the new instant. */
    public Instant advance(Duration d) {
        return instant.updateAndGet(i -> i.plus(d));
    }

    /** Set time to a specific instant. */
    public void setInstant(Instant i) {
        instant.set(i);
    }
}

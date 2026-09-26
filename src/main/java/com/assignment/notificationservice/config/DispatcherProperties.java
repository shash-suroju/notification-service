package com.assignment.notificationservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tick loop, lease and reaper tuning. Bound from {@code notify.dispatcher}.
 *
 * <p>{@code autoStart} is false in the test profile so tests drive the dispatcher with
 * explicit {@code tick()} calls instead of racing a scheduled thread.
 */
@ConfigurationProperties(prefix = "notify.dispatcher")
@Getter
@Setter
public class DispatcherProperties {

    /** Whether the scheduled tick loop starts with the application. */
    private boolean autoStart = true;

    /** Interval between dispatcher ticks, in milliseconds. */
    private long tickIntervalMs = 100;

    /** Rows a weight-1 tenant may claim per tick, per channel. */
    private int baseQuantum = 20;

    /** How long a claim lease is held before the reaper may recover the row. */
    private long leaseDurationSeconds = 60;

    /** Interval between lease reaper sweeps. */
    private long reaperIntervalSeconds = 30;

    public Duration leaseDuration() {
        return Duration.ofSeconds(leaseDurationSeconds);
    }

    public Duration reaperInterval() {
        return Duration.ofSeconds(reaperIntervalSeconds);
    }

    public Duration tickInterval() {
        return Duration.ofMillis(tickIntervalMs);
    }
}

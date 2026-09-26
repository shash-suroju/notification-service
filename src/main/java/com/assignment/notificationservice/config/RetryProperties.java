package com.assignment.notificationservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Exponential-backoff tuning. Bound from {@code notify.retry}.
 * A tenant's own {@code maxAttempts} overrides {@link #defaultMaxAttempts} when set.
 */
@ConfigurationProperties(prefix = "notify.retry")
@Getter
@Setter
public class RetryProperties {

    /** Delay before the first retry, doubled each subsequent attempt. */
    private long baseDelayMs = 2000;

    /** Ceiling on the computed backoff delay. */
    private long maxDelayMs = 300_000;

    /** Retry budget used when the tenant does not specify one. */
    private int defaultMaxAttempts = 5;

    public Duration baseDelay() {
        return Duration.ofMillis(baseDelayMs);
    }

    public Duration maxDelay() {
        return Duration.ofMillis(maxDelayMs);
    }
}

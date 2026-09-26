package com.assignment.notificationservice.configs;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now" for the whole application.
 *
 * <p>Nothing may call {@code Instant.now()} directly: the dispatcher, backoff policy,
 * token buckets and lease expiry all read time from this bean, so tests can swap in a
 * {@code MutableClock} and control time exactly.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

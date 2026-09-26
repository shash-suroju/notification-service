package com.assignment.notificationservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * Replaces the production {@code Clock.systemUTC()} with a {@link MutableClock}.
 *
 * <p>The factory method is deliberately not named {@code clock}: that would collide with
 * {@code ClockConfig#clock} and trip bean-definition-override protection. A distinct name
 * plus {@code @Primary} wins injection regardless of which definition registers first.
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    public Clock testClock(MutableClock mutableClock) {
        return mutableClock;
    }
}

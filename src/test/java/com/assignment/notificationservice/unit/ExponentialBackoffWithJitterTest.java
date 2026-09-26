package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.utils.ExponentialBackoffWithJitter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExponentialBackoffWithJitterTest {

    private static final long BASE = 2_000;
    private static final long CAP = 300_000;

    private final ExponentialBackoffWithJitter backoff = new ExponentialBackoffWithJitter(BASE, CAP);

    @Test
    void firstAttempt_delayAroundBase() {
        for (int i = 0; i < 1000; i++) {
            assertThat(backoff.nextDelay(1)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
        }
    }

    @Test
    void exponentialGrowth() {
        assertThat(backoff.rawDelayMs(1)).isEqualTo(2_000);
        assertThat(backoff.rawDelayMs(2)).isEqualTo(4_000);
        assertThat(backoff.rawDelayMs(3)).isEqualTo(8_000);
        assertThat(backoff.rawDelayMs(4)).isEqualTo(16_000);
        assertThat(backoff.rawDelayMs(5)).isEqualTo(32_000);
    }

    @Test
    void cappedAtMaxDelay() {
        assertThat(backoff.rawDelayMs(20)).isEqualTo(CAP);
        for (int i = 0; i < 1000; i++) {
            assertThat(backoff.nextDelay(20)).isBetween(Duration.ofMillis(CAP / 2), Duration.ofMillis(CAP));
        }
    }

    @Test
    void hugeAttemptNumbers_doNotOverflow() {
        assertThat(backoff.rawDelayMs(64)).isEqualTo(CAP);
        assertThat(backoff.rawDelayMs(1_000)).isEqualTo(CAP);
        assertThat(backoff.nextDelay(Integer.MAX_VALUE)).isBetween(Duration.ofMillis(CAP / 2), Duration.ofMillis(CAP));
    }

    @Test
    void attemptBelowOne_isTreatedAsFirstAttempt() {
        assertThat(backoff.rawDelayMs(0)).isEqualTo(BASE);
        assertThat(backoff.rawDelayMs(-3)).isEqualTo(BASE);
    }

    @Test
    void jitterBounds() {
        for (int attempt = 1; attempt <= 12; attempt++) {
            long raw = backoff.rawDelayMs(attempt);
            for (int i = 0; i < 1000; i++) {
                long delay = backoff.nextDelay(attempt).toMillis();
                assertThat(delay).as("attempt %d", attempt).isBetween(raw / 2, raw);
            }
        }
    }

    @Test
    void jitterActuallySpreadsDelays() {
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            distinct.add(backoff.nextDelay(3).toMillis());
        }
        assertThat(distinct).hasSizeGreaterThan(50);   // not a fixed delay → no thundering herd
    }

    @Test
    void deterministicWithSeededRandom() {
        ExponentialBackoffWithJitter a = new ExponentialBackoffWithJitter(BASE, CAP, new Random(42));
        ExponentialBackoffWithJitter b = new ExponentialBackoffWithJitter(BASE, CAP, new Random(42));
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(a.nextDelay(attempt)).isEqualTo(b.nextDelay(attempt));
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new ExponentialBackoffWithJitter(0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffWithJitter(5000, 1000)).isInstanceOf(IllegalArgumentException.class);
    }
}

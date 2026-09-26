package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.support.MutableClock;
import com.assignment.notificationservice.utils.TokenBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

    private MutableClock clock;
    private TokenBucket bucket;   // 10/s, burst 20

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        bucket = new TokenBucket(10, 20, clock);
    }

    @Test
    void initiallyFull_grantsBurstAmount() {
        assertThat(bucket.tryAcquire(20)).isEqualTo(20);
    }

    @Test
    void emptyBucket_grantsZero() {
        bucket.tryAcquire(20);
        assertThat(bucket.tryAcquire(1)).isZero();
    }

    @Test
    void refillsOverTime() {
        bucket.tryAcquire(20);
        clock.advance(Duration.ofSeconds(1));
        assertThat(bucket.tryAcquire(15)).isEqualTo(10);
    }

    @Test
    void cappedAtBurst() {
        bucket.tryAcquire(20);
        clock.advance(Duration.ofSeconds(10));
        assertThat(bucket.tryAcquire(100)).isEqualTo(20);
    }

    @Test
    void partialGrant() {
        bucket.tryAcquire(20);
        clock.advance(Duration.ofMillis(500));
        assertThat(bucket.tryAcquire(10)).isEqualTo(5);
    }

    @Test
    void fractionalRefillIsNotLost() {
        bucket.tryAcquire(20);
        clock.advance(Duration.ofMillis(50));   // 0.5 tokens
        assertThat(bucket.tryAcquire(1)).isZero();
        clock.advance(Duration.ofMillis(50));   // another 0.5 → 1.0 total
        assertThat(bucket.tryAcquire(1)).isEqualTo(1);
    }

    @Test
    void releaseReturnsTokens() {
        bucket.tryAcquire(10);          // 10 left
        bucket.release(5);              // 15
        assertThat(bucket.tryAcquire(20)).isEqualTo(15);
    }

    @Test
    void releaseCappedAtBurst() {
        bucket.release(10);             // already full
        assertThat(bucket.tryAcquire(100)).isEqualTo(20);
    }

    @Test
    void nonPositiveRequests_grantNothing_andConsumeNothing() {
        assertThat(bucket.tryAcquire(0)).isZero();
        assertThat(bucket.tryAcquire(-5)).isZero();
        assertThat(bucket.available()).isEqualTo(20);
    }

    @Test
    void clockGoingBackwards_doesNotRemoveTokens() {
        bucket.tryAcquire(10);
        clock.setInstant(clock.instant().minusSeconds(5));
        assertThat(bucket.available()).isEqualTo(10);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new TokenBucket(0, 10, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(10, 0, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentAcquirers_neverOverGrant() throws Exception {
        TokenBucket big = new TokenBucket(1, 1000, clock);   // no refill while the clock is frozen
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < 500; i++) {
                    granted.addAndGet(big.tryAcquire(1));
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(granted.get()).isEqualTo(1000);   // 4000 requests, exactly burst granted
    }
}

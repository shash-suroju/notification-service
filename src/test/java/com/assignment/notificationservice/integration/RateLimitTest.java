package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseDispatcherTest;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.support.TestTenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-layer token buckets. Refill is driven by the MutableClock, so "10 per second" is
 * asserted as exact counts, not approximations.
 */
class RateLimitTest extends BaseDispatcherTest {

    @AfterEach
    void restoreGlobalEmailLimit() {
        jdbc.update("UPDATE global_channel_limit SET rate_per_sec = 500, burst = 1000 WHERE channel = 'EMAIL'");
        rateLimiterRegistry.refreshChannel(Channel.EMAIL);
    }

    @Test
    void tenant_rate_limit_is_respected() {
        TestTenant tenant = setupTenant("rate");
        // weight 10 → quantum 50, so the bucket (not the quantum) is the binding limit
        jdbc.update("UPDATE tenant SET rate_limit_per_sec = 10, burst = 10, weight = 10 WHERE id = ?", tenant.id());
        insertPending(tenant.id(), Channel.EMAIL, 100);

        assertThat(tickAndAwait()).as("burst").isEqualTo(10);
        assertThat(tickAndAwait()).as("same instant, bucket empty").isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(tickAndAwait()).as("1 s refill").isEqualTo(10);

        clock.advance(Duration.ofMillis(500));
        assertThat(tickAndAwait()).as("0.5 s refill").isEqualTo(5);

        // Rate-limited work is deferred, not failed: it is still queued, with no attempt spent.
        assertThat(countWithStatus(tenant.id(), "SENT")).isEqualTo(25);
        assertThat(countWithStatus(tenant.id(), "PENDING")).isEqualTo(75);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE tenant_id = ? AND status = 'PENDING' AND attempt_count > 0",
                Integer.class, tenant.id())).isZero();
    }

    @Test
    void channel_rate_limit_caps_across_tenants() {
        jdbc.update("UPDATE global_channel_limit SET rate_per_sec = 5, burst = 5 WHERE channel = 'EMAIL'");
        rateLimiterRegistry.refreshChannel(Channel.EMAIL);
        TestTenant a = setupTenant("chan-a");
        TestTenant b = setupTenant("chan-b");
        insertPending(a.id(), Channel.EMAIL, 50);
        insertPending(b.id(), Channel.EMAIL, 50);

        // Each tenant alone may take 5 per tick, but the channel only allows 5 in total.
        assertThat(tickAndAwait()).isEqualTo(5);

        clock.advance(Duration.ofSeconds(1));
        assertThat(tickAndAwait()).isEqualTo(5);

        assertThat(countWithStatus("SENT")).isEqualTo(10);
        // The rotating cursor gives each tenant a turn at the scarce channel budget.
        assertThat(countWithStatus(a.id(), "SENT")).isEqualTo(5);
        assertThat(countWithStatus(b.id(), "SENT")).isEqualTo(5);
    }

    @Test
    void channel_limit_does_not_throttle_other_channels() {
        jdbc.update("UPDATE global_channel_limit SET rate_per_sec = 1, burst = 1 WHERE channel = 'EMAIL'");
        rateLimiterRegistry.refreshChannel(Channel.EMAIL);
        TestTenant tenant = setupTenant("chan-mix");
        insertPending(tenant.id(), Channel.EMAIL, 5);
        insertPending(tenant.id(), Channel.SMS, 5);

        assertThat(tickAndAwait()).isEqualTo(1 + 5);   // 1 EMAIL (capped) + 5 SMS (quantum)
        assertThat(smsSender.getCallCount()).isEqualTo(5);
        assertThat(emailSender.getCallCount()).isEqualTo(1);
    }
}

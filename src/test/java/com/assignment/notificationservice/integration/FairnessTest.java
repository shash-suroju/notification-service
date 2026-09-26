package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseDispatcherTest;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.support.TestTenant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Weighted round-robin across tenants. Test profile base quantum: 5 per tenant per tick. */
class FairnessTest extends BaseDispatcherTest {

    @Test
    void small_tenant_finishes_before_large_tenant_is_half_done() {
        TestTenant large = setupTenant("fair-large");
        TestTenant small = setupTenant("fair-small");
        insertPending(large.id(), Channel.EMAIL, 2000);
        insertPending(small.id(), Channel.EMAIL, 20);

        for (int tick = 0; tick < 4; tick++) {
            tickAndAwait();
        }

        // The small tenant is completely done after 4 ticks despite a 2000-row backlog ahead of it...
        assertThat(countWithStatus(small.id(), "SENT")).isEqualTo(20);
        // ...because the large tenant received the same per-tick share, not all of it.
        assertThat(countWithStatus(large.id(), "SENT")).isEqualTo(20);
        assertThat(countWithStatus(large.id(), "PENDING")).isEqualTo(1980);

        // Once the small tenant is empty, the large one keeps being served.
        tickAndAwait();
        assertThat(countWithStatus(large.id(), "SENT")).isEqualTo(25);
    }

    @Test
    void weighted_tenant_gets_proportional_throughput() {
        TestTenant light = setupTenant("weight-1");
        TestTenant heavy = setupTenant("weight-3");
        jdbc.update("UPDATE tenant SET weight = 3 WHERE id = ?", heavy.id());
        insertPending(light.id(), Channel.EMAIL, 100);
        insertPending(heavy.id(), Channel.EMAIL, 100);

        for (int tick = 0; tick < 3; tick++) {
            tickAndAwait();
        }

        int lightSent = countWithStatus(light.id(), "SENT");
        int heavySent = countWithStatus(heavy.id(), "SENT");
        assertThat(lightSent).isEqualTo(15);          // 3 ticks × 5 × weight 1
        assertThat(heavySent).isEqualTo(45);          // 3 ticks × 5 × weight 3
        assertThat(heavySent).isEqualTo(3 * lightSent);
    }

    @Test
    void every_tenant_with_due_work_is_served_every_tick() {
        TestTenant[] tenants = {setupTenant("rr-1"), setupTenant("rr-2"), setupTenant("rr-3"), setupTenant("rr-4")};
        for (TestTenant t : tenants) {
            insertPending(t.id(), Channel.EMAIL, 50);
        }

        tickAndAwait();

        for (TestTenant t : tenants) {
            assertThat(countWithStatus(t.id(), "SENT")).as(t.slug()).isEqualTo(5);
        }
    }
}

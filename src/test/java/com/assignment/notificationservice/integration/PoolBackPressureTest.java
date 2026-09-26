package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseDispatcherTest;
import com.assignment.notificationservice.dtos.ClaimedNotification;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.WorkClaimer;
import com.assignment.notificationservice.support.TestTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Bounded pools push back instead of buffering. Test-profile EMAIL pool: 2 threads + 20 queue
 * slots. The pool is filled with blocker tasks to control exactly how much capacity is left.
 */
class PoolBackPressureTest extends BaseDispatcherTest {

    @Autowired
    private WorkClaimer workClaimer;

    private TestTenant tenant;
    private ThreadPoolExecutor emailPool;

    @BeforeEach
    void setUp() {
        tenant = setupTenant("pool");
        emailPool = workerPools.get(Channel.EMAIL);
    }

    @Test
    void dispatcher_claims_only_what_the_pool_can_take() {
        CountDownLatch gate = new CountDownLatch(1);
        try {
            occupyLeaving(3, gate);
            assertThat(workerPools.freeSlots(Channel.EMAIL)).isEqualTo(3);
            insertPending(tenant.id(), Channel.EMAIL, 10);

            assertThat(scheduler.tick()).isEqualTo(3);

            // Only 3 rows were claimed; the other 7 were never touched — no attempt spent, no lease.
            assertThat(countWithStatus(tenant.id(), "PROCESSING")).isEqualTo(3);
            assertThat(countWithStatus(tenant.id(), "PENDING")).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE tenant_id = ? AND status = 'PENDING' AND attempt_count > 0",
                    Integer.class, tenant.id())).isZero();
        } finally {
            gate.countDown();
        }
        awaitNoInFlight();
        assertThat(countWithStatus(tenant.id(), "SENT")).isEqualTo(3);

        // Capacity is back: the rest drain on later ticks. Nothing was lost.
        tickAndAwait();
        tickAndAwait();
        assertThat(countWithStatus(tenant.id(), "SENT")).isEqualTo(10);
    }

    @Test
    void saturated_pool_claims_nothing() {
        CountDownLatch gate = new CountDownLatch(1);
        try {
            occupyLeaving(0, gate);
            assertThat(workerPools.freeSlots(Channel.EMAIL)).isZero();
            insertPending(tenant.id(), Channel.EMAIL, 5);

            assertThat(scheduler.tick()).isZero();
            assertThat(countWithStatus(tenant.id(), "PENDING")).isEqualTo(5);
        } finally {
            gate.countDown();
        }
        assertThat(tickAndAwait()).isEqualTo(5);
    }

    /**
     * The path taken when {@code execute()} still rejects (capacity changed between the
     * free-slot check and submission): the claim is undone without consuming an attempt.
     */
    @Test
    void pool_rejection_releases_row_back_to_pending() {
        insertPending(tenant.id(), Channel.EMAIL, 3);
        List<ClaimedNotification> claimed =
                workClaimer.claim(tenant.id(), Channel.EMAIL, 3, workClaimer.newLockToken(), Duration.ofSeconds(60));
        assertThat(claimed).hasSize(3);

        for (ClaimedNotification n : claimed) {
            assertThat(workClaimer.releaseToPending(n)).isTrue();
            assertThat(workClaimer.releaseToPending(n)).as("release is fenced: only once").isFalse();
        }

        assertThat(countWithStatus(tenant.id(), "PENDING")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sum(attempt_count) FROM notification WHERE tenant_id = ?",
                Integer.class, tenant.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE tenant_id = ? AND locked_by IS NOT NULL",
                Integer.class, tenant.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE tenant_id = ? AND reason = 'pool_rejected'",
                Integer.class, tenant.id())).isEqualTo(3);

        // Delivered later as attempt 1 — the rejected claim did not use up an attempt.
        assertThat(tickAndAwait()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT attempt_no FROM delivery_attempt d JOIN notification n ON n.id = d.notification_id WHERE n.tenant_id = ?",
                Integer.class, tenant.id())).containsExactly(1, 1, 1);
    }

    /**
     * Fills the pool with tasks blocked on {@code gate} until exactly {@code free} slots remain.
     * Threads are occupied first, and only then the queue: submitting everything at once can
     * overflow the queue before the (idle) threads start draining it.
     */
    private void occupyLeaving(int free, CountDownLatch gate) {
        Runnable blocker = () -> {
            try {
                gate.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        for (int i = 0; i < emailPool.getMaximumPoolSize(); i++) {
            emailPool.execute(blocker);
        }
        await().atMost(AWAIT).until(() -> emailPool.getActiveCount() == emailPool.getMaximumPoolSize()
                && emailPool.getQueue().isEmpty());
        int toQueue = emailPool.getQueue().remainingCapacity() - free;
        for (int i = 0; i < toQueue; i++) {
            emailPool.execute(blocker);
        }
    }
}

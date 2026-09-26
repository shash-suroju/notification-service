package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseDispatcherTest;
import com.assignment.notificationservice.dtos.ClaimedNotification;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.services.WorkClaimer;
import com.assignment.notificationservice.support.TestTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** FOR UPDATE SKIP LOCKED under real contention: every row is claimed once, by one claimer. */
class ConcurrentClaimTest extends BaseDispatcherTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    private WorkClaimer workClaimer;

    private TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = setupTenant("claim");
    }

    @Test
    void one_thousand_rows_claimed_exactly_once_by_concurrent_workers() throws Exception {
        insertPending(tenant.id(), Channel.EMAIL, 1000);

        int threads = 8;
        Map<UUID, String> ownerByRow = new ConcurrentHashMap<>();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger totalClaimed = new AtomicInteger();
        Set<Integer> threadsThatClaimed = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadNo = t;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    while (true) {
                        String token = workClaimer.newLockToken();
                        List<ClaimedNotification> batch = workClaimer.claim(tenant.id(), Channel.EMAIL, 25, token, LEASE);
                        if (batch.isEmpty()) {
                            return null;
                        }
                        threadsThatClaimed.add(threadNo);
                        totalClaimed.addAndGet(batch.size());
                        for (ClaimedNotification n : batch) {
                            if (ownerByRow.putIfAbsent(n.id(), token) != null) {
                                duplicates.incrementAndGet();
                            }
                        }
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(duplicates.get()).as("rows handed to two claimers").isZero();
        assertThat(totalClaimed.get()).isEqualTo(1000);
        assertThat(ownerByRow).hasSize(1000);
        assertThat(threadsThatClaimed).as("claimers that got work").hasSizeGreaterThan(1);

        // The database agrees: every row PROCESSING, one attempt, locked by exactly the claimer that got it.
        assertThat(countWithStatus(tenant.id(), "PROCESSING")).isEqualTo(1000);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, locked_by, attempt_count FROM notification WHERE tenant_id = ?", tenant.id());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("locked_by")).isEqualTo(ownerByRow.get((UUID) r.get("id")));
            assertThat(r.get("attempt_count")).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_event WHERE tenant_id = ? AND reason = 'claimed'",
                Integer.class, tenant.id())).isEqualTo(1000);
    }

    @Test
    void claim_respects_batch_size_and_next_attempt_at() {
        insertPending(tenant.id(), Channel.EMAIL, 5);
        List<UUID> future = insertPending(tenant.id(), Channel.EMAIL, 3);
        for (UUID id : future) {
            jdbc.update("UPDATE notification SET next_attempt_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant().plus(Duration.ofHours(1))), id);
        }

        List<ClaimedNotification> first = workClaimer.claim(tenant.id(), Channel.EMAIL, 3, workClaimer.newLockToken(), LEASE);
        List<ClaimedNotification> second = workClaimer.claim(tenant.id(), Channel.EMAIL, 10, workClaimer.newLockToken(), LEASE);

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(2);   // only the remaining due rows; future rows are untouched
        assertThat(future).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("PENDING"));
        assertThat(first).allSatisfy(n -> {
            assertThat(n.attemptCount()).isEqualTo(1);
            assertThat(n.previousStatus()).isEqualTo(NotificationStatus.PENDING);
        });
    }

    @Test
    void claim_is_scoped_to_one_tenant_and_one_channel() {
        TestTenant other = setupTenant("claim-other");
        insertPending(tenant.id(), Channel.EMAIL, 2);
        insertPending(tenant.id(), Channel.SMS, 2);
        insertPending(other.id(), Channel.EMAIL, 2);

        List<ClaimedNotification> claimed = workClaimer.claim(tenant.id(), Channel.EMAIL, 100, workClaimer.newLockToken(), LEASE);

        assertThat(claimed).hasSize(2).allSatisfy(n -> {
            assertThat(n.tenantId()).isEqualTo(tenant.id());
            assertThat(n.channel()).isEqualTo(Channel.EMAIL);
        });
        assertThat(countWithStatus(other.id(), "PENDING")).isEqualTo(2);
    }

    @Test
    void every_claim_gets_a_distinct_lease_token() {
        Set<String> tokens = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> workClaimer.newLockToken()).collect(Collectors.toSet());

        assertThat(tokens).hasSize(100).allSatisfy(t -> assertThat(t.length()).isLessThanOrEqualTo(50));
    }
}

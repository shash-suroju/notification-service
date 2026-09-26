package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseDispatcherTest;
import com.assignment.notificationservice.dtos.ClaimedNotification;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.services.LeaseReaper;
import com.assignment.notificationservice.services.OutcomeRecorder;
import com.assignment.notificationservice.support.TestSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Lease expiry and fencing. Test profile lease: 5 s. The hung worker is a real thread
 * blocked inside the sender, not a simulation of one.
 */
class LeaseRecoveryTest extends BaseDispatcherTest {

    @Autowired
    private LeaseReaper leaseReaper;

    @Autowired
    private OutcomeRecorder outcomeRecorder;

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("lease");
    }

    /**
     * The full failure story: a worker hangs, its lease expires, the reaper requeues the row,
     * a fresh claim delivers it — and when the hung worker finally returns, its late write is
     * fenced out instead of overwriting the real outcome.
     */
    @Test
    void expired_lease_is_reaped_and_requeued_and_the_late_worker_is_fenced_out() {
        UUID id = submit();
        CountDownLatch hungWorker = new CountDownLatch(1);
        emailSender.programBlocking(id, hungWorker, new SendResult.Success("late-provider-id"));
        long fencedBefore = outcomeRecorder.getFencedOutWrites();

        try {
            assertThat(scheduler.tick()).isEqualTo(1);
            await().atMost(AWAIT).until(() -> emailSender.getCallCount() == 1);   // worker is inside send()
            assertThat(statusOf(id)).isEqualTo("PROCESSING");

            clock.advance(Duration.ofSeconds(6));    // past the 5 s lease
            assertThat(leaseReaper.reap()).isEqualTo(1);

            Map<String, Object> reaped = jdbc.queryForMap(
                    "SELECT status, locked_by, next_attempt_at FROM notification WHERE id = ?", id);
            assertThat(reaped.get("status")).isEqualTo("RETRYING");
            assertThat(reaped.get("locked_by")).isNull();
            assertThat(((Timestamp) reaped.get("next_attempt_at")).toInstant()).isEqualTo(clock.instant());
            Map<String, Object> abandoned = jdbc.queryForMap(
                    "SELECT attempt_no, outcome, error_code FROM delivery_attempt WHERE notification_id = ?", id);
            assertThat(abandoned).containsEntry("attempt_no", 1).containsEntry("outcome", "ABANDONED")
                    .containsEntry("error_code", "LEASE_EXPIRED");
            assertThat(lastEvent(id)).isEqualTo("PROCESSING>RETRYING lease_expired REAPER");

            // A fresh claim delivers it (the other EMAIL worker thread is free).
            assertThat(scheduler.tick()).isEqualTo(1);
            await().atMost(AWAIT).until(() -> "SENT".equals(statusOf(id)));

            // Now the hung worker returns with its own "success" — and must be fenced out.
            hungWorker.countDown();
            await().atMost(AWAIT).until(() -> outcomeRecorder.getFencedOutWrites() == fencedBefore + 1);
            awaitNoInFlight();
        } finally {
            hungWorker.countDown();
        }

        assertThat(statusOf(id)).isEqualTo("SENT");
        List<Map<String, Object>> attempts = jdbc.queryForList(
                "SELECT attempt_no, outcome, provider_message_id FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no", id);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0)).containsEntry("outcome", "ABANDONED");
        assertThat(attempts.get(1)).containsEntry("attempt_no", 2).containsEntry("outcome", "SUCCESS");
        assertThat(attempts.get(1).get("provider_message_id")).isNotEqualTo("late-provider-id");
    }

    @Test
    void stale_worker_write_is_rejected_after_reap() {
        UUID id = submit();
        forceProcessing(id, "stale-worker", 1, clock.instant().minusSeconds(1));

        assertThat(leaseReaper.reap()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("RETRYING");

        boolean written = outcomeRecorder.record(claimed(id, "stale-worker", 1), new SendResult.Success("stale"), clock.instant());

        assertThat(written).isFalse();
        assertThat(statusOf(id)).isEqualTo("RETRYING");                   // not overwritten to SENT
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_attempt WHERE notification_id = ? AND outcome = 'SUCCESS'",
                Integer.class, id)).isZero();
    }

    @Test
    void fence_accepts_only_the_current_lease_holder() {
        UUID id = submit();
        forceProcessing(id, "current-lease", 1, clock.instant().plusSeconds(60));

        assertThat(outcomeRecorder.record(claimed(id, "someone-else", 1), new SendResult.Success("x"), clock.instant())).isFalse();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");

        assertThat(outcomeRecorder.record(claimed(id, "current-lease", 1), new SendResult.Success("y"), clock.instant())).isTrue();
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    void lease_expiry_on_the_last_attempt_fails_the_notification() {
        UUID id = submit();
        forceProcessing(id, "dead-worker", 5, clock.instant().minusSeconds(1));   // 5 of 5 attempts used

        leaseReaper.reap();

        Map<String, Object> row = jdbc.queryForMap("SELECT status, failure_reason FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("LEASE_EXPIRED");
        assertThat(lastEvent(id)).isEqualTo("PROCESSING>FAILED lease_expired_retries_exhausted REAPER");
    }

    @Test
    void unexpired_lease_is_left_alone() {
        UUID id = submit();
        forceProcessing(id, "busy-worker", 1, clock.instant().plusSeconds(30));

        assertThat(leaseReaper.reap()).isZero();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");
    }

    @Test
    void tick_reaps_and_redelivers_without_manual_intervention() {
        UUID id = submit();
        forceProcessing(id, "crashed-instance", 1, clock.instant().plusSeconds(5));
        clock.advance(Duration.ofSeconds(10));

        tickAndAwait();   // reaps (RETRYING, due now), then claims and sends in the same tick

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(jdbc.queryForList("SELECT outcome FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no",
                String.class, id)).containsExactly("ABANDONED", "SUCCESS");
    }

    // ---- helpers ----

    private UUID submit() {
        return idOf(send(sender, newIdempotencyKey(), welcomeEmail("lease@example.com")));
    }

    /** Puts a row into the state a claim leaves it in, as if a worker had it in flight. */
    private void forceProcessing(UUID id, String lockToken, int attemptCount, java.time.Instant lockedUntil) {
        jdbc.update("UPDATE notification SET status = 'PROCESSING', locked_by = ?, locked_until = ?, attempt_count = ? WHERE id = ?",
                lockToken, Timestamp.from(lockedUntil), attemptCount, id);
    }

    private ClaimedNotification claimed(UUID id, String lockToken, int attemptCount) {
        return new ClaimedNotification(id, sender.tenant().id(), sender.tenant().slug(), Channel.EMAIL,
                "lease@example.com", "s", "b", attemptCount, 5, NotificationStatus.PENDING, lockToken);
    }

    private String lastEvent(UUID id) {
        return jdbc.queryForObject("""
                SELECT from_status || '>' || to_status || ' ' || reason || ' ' || actor FROM notification_event
                WHERE notification_id = ? ORDER BY occurred_at DESC, seq DESC LIMIT 1""", String.class, id);
    }
}

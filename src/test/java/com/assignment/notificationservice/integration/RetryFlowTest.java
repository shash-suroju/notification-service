package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseDispatcherTest;
import com.assignment.notificationservice.dtos.SendResult.PermanentFailure;
import com.assignment.notificationservice.dtos.SendResult.TransientFailure;
import com.assignment.notificationservice.models.enums.AttemptOutcome;
import com.assignment.notificationservice.support.TestSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retry, backoff and give-up behaviour. Test profile: base delay 1 s, max delay 10 s.
 * Time only moves when the test moves the clock, so "not yet due" is checked exactly.
 */
class RetryFlowTest extends BaseDispatcherTest {

    private static final TransientFailure TIMEOUT = new TransientFailure("TIMEOUT", "Provider timed out");

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("retry");
    }

    @Test
    void transient_failure_retries_then_succeeds() {
        UUID id = submit();
        emailSender.program(id, TIMEOUT, TIMEOUT);   // then default: success

        tickAndAwait();
        assertThat(statusOf(id)).isEqualTo("RETRYING");

        // Backoff has not expired: another tick does not retry.
        assertThat(tickAndAwait()).isZero();
        assertThat(emailSender.getCallCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));        // attempt-1 delay is at most 1 s
        tickAndAwait();
        assertThat(statusOf(id)).isEqualTo("RETRYING");

        clock.advance(Duration.ofSeconds(2));        // attempt-2 delay is at most 2 s
        tickAndAwait();
        assertThat(statusOf(id)).isEqualTo("SENT");

        assertThat(attemptOutcomes(id)).containsExactly(
                AttemptOutcome.TRANSIENT_FAILURE, AttemptOutcome.TRANSIENT_FAILURE, AttemptOutcome.SUCCESS);
        assertThat(jdbc.queryForList("SELECT attempt_no FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no",
                Integer.class, id)).containsExactly(1, 2, 3);
        Map<String, Object> row = jdbc.queryForMap("SELECT attempt_count, last_error_code FROM notification WHERE id = ?", id);
        assertThat(row.get("attempt_count")).isEqualTo(3);
        assertThat(row.get("last_error_code")).isNull();

        assertThat(jdbc.queryForList("""
                        SELECT from_status || '>' || to_status || ' ' || reason FROM notification_event
                        WHERE notification_id = ? AND from_status IS NOT NULL ORDER BY occurred_at, seq""",
                String.class, id)).containsExactly(
                "PENDING>PROCESSING claimed",
                "PROCESSING>RETRYING transient_failure:TIMEOUT",
                "RETRYING>PROCESSING claimed",
                "PROCESSING>RETRYING transient_failure:TIMEOUT",
                "RETRYING>PROCESSING claimed",
                "PROCESSING>SENT provider_success");
    }

    @Test
    void retries_exhausted_becomes_failed() {
        jdbc.update("UPDATE tenant SET max_attempts = 3 WHERE id = ?", sender.tenant().id());
        UUID id = submit();                                   // maxAttempts is copied at accept time
        emailSender.programDefault(TIMEOUT);

        for (int i = 0; i < 3; i++) {
            tickAndAwait();
            clock.advance(Duration.ofSeconds(20));           // beyond the 10 s max delay
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, failure_reason, last_error_code, attempt_count FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(row.get("last_error_code")).isEqualTo("TIMEOUT");
        assertThat(row.get("attempt_count")).isEqualTo(3);
        assertThat(attemptOutcomes(id)).containsExactly(
                AttemptOutcome.TRANSIENT_FAILURE, AttemptOutcome.TRANSIENT_FAILURE, AttemptOutcome.TRANSIENT_FAILURE);
        assertThat(lastEventReason(id)).isEqualTo("retries_exhausted");

        // FAILED is terminal for the dispatcher.
        tickAndAwait();
        assertThat(emailSender.getCallCount()).isEqualTo(3);
    }

    @Test
    void permanent_failure_no_retry() {
        UUID id = submit();
        emailSender.program(id, new PermanentFailure("INVALID_RECIPIENT", "Mailbox does not exist"));

        tickAndAwait();
        clock.advance(Duration.ofMinutes(10));
        tickAndAwait();

        Map<String, Object> row = jdbc.queryForMap("SELECT status, failure_reason, last_error_code FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("PERMANENT_FAILURE: Mailbox does not exist");
        assertThat(row.get("last_error_code")).isEqualTo("INVALID_RECIPIENT");
        assertThat(attemptOutcomes(id)).containsExactly(AttemptOutcome.PERMANENT_FAILURE);
        assertThat(lastEventReason(id)).isEqualTo("permanent_failure:INVALID_RECIPIENT");
        assertThat(emailSender.getCallCount()).isEqualTo(1);
    }

    @Test
    void unknown_permanent_error_code_is_retried_not_failed() {
        UUID id = submit();
        emailSender.program(id, new PermanentFailure("SOMETHING_NEW", "?"));

        tickAndAwait();

        assertThat(statusOf(id)).isEqualTo("RETRYING");
        assertThat(attemptOutcomes(id)).containsExactly(AttemptOutcome.TRANSIENT_FAILURE);
    }

    @Test
    void backoff_spacing_follows_exponential_policy() {
        UUID id = submit();
        emailSender.program(id, TIMEOUT, TIMEOUT, TIMEOUT);   // then success

        for (int attempt = 1; attempt <= 3; attempt++) {
            Instant failedAt = clock.instant();
            tickAndAwait();
            assertThat(statusOf(id)).isEqualTo("RETRYING");

            long raw = Math.min(10_000, 1_000L << (attempt - 1));   // 1 s, 2 s, 4 s
            Duration delay = Duration.between(failedAt, nextAttemptAt(id));
            assertThat(delay.toMillis()).as("delay after attempt %d", attempt).isBetween(raw / 2, raw);

            // One millisecond early: not due. Exactly on time: claimed.
            clock.advance(delay.minusMillis(1));
            assertThat(tickAndAwait()).as("tick 1 ms before due").isZero();
            clock.advance(Duration.ofMillis(1));
            if (attempt < 3) {
                continue;   // next loop iteration performs the (failing) attempt
            }
            assertThat(tickAndAwait()).isEqualTo(1);
        }

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(emailSender.callsFor(id)).isEqualTo(4);
    }

    @Test
    void sender_exception_is_recorded_as_transient_failure() {
        UUID id = submit();
        emailSender.programThrowing(id, new IllegalStateException("client bug"));

        tickAndAwait();

        assertThat(statusOf(id)).isEqualTo("RETRYING");
        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT outcome, error_code, error_message FROM delivery_attempt WHERE notification_id = ?", id);
        assertThat(attempt.get("outcome")).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempt.get("error_code")).isEqualTo("SENDER_EXCEPTION");
        assertThat((String) attempt.get("error_message")).contains("client bug");
    }

    private UUID submit() {
        return idOf(send(sender, newIdempotencyKey(), welcomeEmail("retry@example.com")));
    }

    private List<AttemptOutcome> attemptOutcomes(UUID id) {
        return jdbc.queryForList("SELECT outcome FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no",
                String.class, id).stream().map(AttemptOutcome::valueOf).toList();
    }

    private String lastEventReason(UUID id) {
        return jdbc.queryForObject("""
                SELECT reason FROM notification_event WHERE notification_id = ?
                ORDER BY occurred_at DESC, seq DESC LIMIT 1""", String.class, id);
    }
}

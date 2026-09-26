package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.DispatchConstants;
import com.assignment.notificationservice.constants.EventActors;
import com.assignment.notificationservice.dtos.ClaimedNotification;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.AttemptOutcome;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.utils.ExponentialBackoffWithJitter;
import com.assignment.notificationservice.utils.FailureClassifier;
import com.assignment.notificationservice.utils.SqlTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the result of one delivery attempt — the <b>fenced write</b>.
 *
 * <p>The UPDATE only applies if the row is still PROCESSING <em>under this claim's lease
 * token</em>. If the lease expired and the reaper recovered the row (and perhaps another claim
 * took it), the late worker's write matches nothing and is discarded. The provider already
 * deduplicates on the notification ID, so no double delivery results.
 *
 * <p>Status change, attempt row and audit event commit together or not at all.
 */
@Component
@RequiredArgsConstructor
public class OutcomeRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutcomeRecorder.class);

    private static final String FENCED_UPDATE_SQL = """
            UPDATE notification
            SET status          = :newStatus,
                sent_at         = CAST(:sentAt AS timestamptz),
                next_attempt_at = COALESCE(CAST(:nextAttemptAt AS timestamptz), next_attempt_at),
                last_error_code = :errorCode,
                failure_reason  = :failureReason,
                locked_by       = NULL,
                locked_until    = NULL,
                updated_at      = :now
            WHERE id = :id
              AND status = 'PROCESSING'
              AND locked_by = :lockToken
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DispatchAuditWriter auditWriter;
    private final ExponentialBackoffWithJitter backoff;
    private final Clock clock;

    private final AtomicLong fencedOutWrites = new AtomicLong();

    /**
     * @param startedAt when the provider call began (for the attempt row and latency)
     * @return true if recorded; false if this worker's lease was lost and the write was fenced out
     */
    @Transactional
    public boolean record(ClaimedNotification n, SendResult result, Instant startedAt) {
        Instant now = clock.instant();
        Decision d = decide(n, result, now);

        if (!NotificationStateMachine.isAllowed(NotificationStatus.PROCESSING, d.newStatus)) {
            throw new IllegalStateException("Illegal outcome transition PROCESSING → " + d.newStatus);
        }

        int updated = jdbc.update(FENCED_UPDATE_SQL, new MapSqlParameterSource()
                .addValue("newStatus", d.newStatus.name())
                .addValue("sentAt", SqlTime.ts(d.sentAt), Types.TIMESTAMP)
                .addValue("nextAttemptAt", SqlTime.ts(d.nextAttemptAt), Types.TIMESTAMP)
                .addValue("errorCode", d.errorCode, Types.VARCHAR)
                .addValue("failureReason", d.failureReason, Types.VARCHAR)
                .addValue("now", SqlTime.ts(now), Types.TIMESTAMP)
                .addValue("id", n.id())
                .addValue("lockToken", n.lockToken()));

        if (updated == 0) {
            fencedOutWrites.incrementAndGet();
            log.warn("Discarding stale outcome {} for notification {}: lease {} no longer held",
                    result.getClass().getSimpleName(), n.id(), n.lockToken());
            return false;
        }

        long latencyMs = Math.max(0, Duration.between(startedAt, now).toMillis());
        auditWriter.attempt(new DispatchAuditWriter.Attempt(
                n.id(), n.attemptCount(), startedAt, now, d.attemptOutcome,
                d.errorCode, d.errorMessage, d.providerMessageId, latencyMs));
        auditWriter.event(new DispatchAuditWriter.Event(
                n.id(), n.tenantId(), NotificationStatus.PROCESSING, d.newStatus,
                d.reason, EventActors.DISPATCHER, now));
        return true;
    }

    /** Number of outcome writes rejected by the fence since startup. */
    public long getFencedOutWrites() {
        return fencedOutWrites.get();
    }

    private Decision decide(ClaimedNotification n, SendResult result, Instant now) {
        return switch (result) {
            case SendResult.Success s -> new Decision(NotificationStatus.SENT, now, null, null, null, null,
                    s.providerMessageId(), AttemptOutcome.SUCCESS, DispatchConstants.REASON_PROVIDER_SUCCESS);

            case SendResult.PermanentFailure p when FailureClassifier.isPermanent(p) -> new Decision(
                    NotificationStatus.FAILED, null, null, p.errorCode(),
                    truncate(DispatchConstants.FAILURE_PERMANENT_PREFIX + p.message()), truncate(p.message()),
                    null, AttemptOutcome.PERMANENT_FAILURE,
                    DispatchConstants.REASON_PERMANENT_FAILURE_PREFIX + p.errorCode());

            // Transient, or "permanent" with an error code we don't recognise → retry if budget remains.
            case SendResult.TransientFailure t -> retryOrExhaust(n, t.errorCode(), t.message(), now);
            case SendResult.PermanentFailure p -> retryOrExhaust(n, p.errorCode(), p.message(), now);
        };
    }

    private Decision retryOrExhaust(ClaimedNotification n, String errorCode, String message, Instant now) {
        if (n.attemptCount() >= n.maxAttempts()) {
            return new Decision(NotificationStatus.FAILED, null, null, errorCode,
                    DispatchConstants.FAILURE_RETRIES_EXHAUSTED, truncate(message), null,
                    AttemptOutcome.TRANSIENT_FAILURE, DispatchConstants.REASON_RETRIES_EXHAUSTED);
        }
        Instant nextAttemptAt = now.plus(backoff.nextDelay(n.attemptCount()));
        return new Decision(NotificationStatus.RETRYING, null, nextAttemptAt, errorCode, null,
                truncate(message), null, AttemptOutcome.TRANSIENT_FAILURE,
                DispatchConstants.REASON_TRANSIENT_FAILURE_PREFIX + errorCode);
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= DispatchConstants.MAX_ERROR_TEXT_LENGTH) {
            return s;
        }
        return s.substring(0, DispatchConstants.MAX_ERROR_TEXT_LENGTH);
    }

    private record Decision(NotificationStatus newStatus, Instant sentAt, Instant nextAttemptAt,
                            String errorCode, String failureReason, String errorMessage,
                            String providerMessageId, AttemptOutcome attemptOutcome, String reason) {
    }
}

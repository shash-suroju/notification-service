package com.assignment.notificationservice.services;

import com.assignment.notificationservice.models.enums.AttemptOutcome;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.utils.SqlTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Audit and attempt rows for the dispatcher's native-SQL status changes.
 *
 * <p>The claim, outcome and reaper paths move status with fenced SQL rather than through the
 * entity, so they cannot call {@link NotificationStateMachine#transition}. Instead every
 * event written here is checked against the same whitelist ({@link NotificationStateMachine#isAllowed}):
 * an illegal transition throws and rolls back the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY}: these rows must commit atomically with the status change.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class DispatchAuditWriter {

    private static final String INSERT_EVENT = """
            INSERT INTO notification_event
                (id, notification_id, tenant_id, from_status, to_status, reason, actor, occurred_at)
            VALUES (:id, :notificationId, :tenantId, :fromStatus, :toStatus, :reason, :actor, :occurredAt)
            """;

    private static final String INSERT_ATTEMPT = """
            INSERT INTO delivery_attempt
                (id, notification_id, attempt_no, started_at, finished_at,
                 outcome, error_code, error_message, provider_message_id, latency_ms)
            VALUES (:id, :notificationId, :attemptNo, :startedAt, :finishedAt,
                    :outcome, :errorCode, :errorMessage, :providerMessageId, :latencyMs)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record Event(UUID notificationId, UUID tenantId, NotificationStatus from, NotificationStatus to,
                        String reason, String actor, Instant occurredAt) {
    }

    public record Attempt(UUID notificationId, int attemptNo, Instant startedAt, Instant finishedAt,
                          AttemptOutcome outcome, String errorCode, String errorMessage,
                          String providerMessageId, long latencyMs) {
    }

    /** Inserts in list order; {@code seq} preserves that order for same-instant events. */
    public void events(List<Event> events) {
        if (events.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = events.stream().map(DispatchAuditWriter::params).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_EVENT, batch);
    }

    public void event(Event event) {
        jdbc.update(INSERT_EVENT, params(event));
    }

    public void attempts(List<Attempt> attempts) {
        if (attempts.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = attempts.stream().map(DispatchAuditWriter::params).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_ATTEMPT, batch);
    }

    public void attempt(Attempt attempt) {
        jdbc.update(INSERT_ATTEMPT, params(attempt));
    }

    private static SqlParameterSource params(Event e) {
        if (!NotificationStateMachine.isAllowed(e.from(), e.to())) {
            throw new IllegalStateException("Illegal transition: " + e.from() + " → " + e.to()
                    + " for notification " + e.notificationId());
        }
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("notificationId", e.notificationId())
                .addValue("tenantId", e.tenantId())
                .addValue("fromStatus", e.from().name())
                .addValue("toStatus", e.to().name())
                .addValue("reason", e.reason())
                .addValue("actor", e.actor())
                .addValue("occurredAt", SqlTime.ts(e.occurredAt()), Types.TIMESTAMP);
    }

    private static SqlParameterSource params(Attempt a) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("notificationId", a.notificationId())
                .addValue("attemptNo", a.attemptNo())
                .addValue("startedAt", SqlTime.ts(a.startedAt()), Types.TIMESTAMP)
                .addValue("finishedAt", SqlTime.ts(a.finishedAt()), Types.TIMESTAMP)
                .addValue("outcome", a.outcome().name())
                .addValue("errorCode", a.errorCode(), Types.VARCHAR)
                .addValue("errorMessage", a.errorMessage(), Types.VARCHAR)
                .addValue("providerMessageId", a.providerMessageId(), Types.VARCHAR)
                .addValue("latencyMs", a.latencyMs());
    }
}

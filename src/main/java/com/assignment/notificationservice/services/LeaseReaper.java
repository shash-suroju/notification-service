package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.DispatchConstants;
import com.assignment.notificationservice.constants.EventActors;
import com.assignment.notificationservice.models.enums.AttemptOutcome;
import com.assignment.notificationservice.models.enums.NotificationStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recovers rows stuck in PROCESSING because their worker hung or the process died.
 *
 * <p>A row whose lease has expired goes to RETRYING, due immediately, with an ABANDONED
 * attempt recorded — or to FAILED if that attempt was its last. Clearing {@code locked_by}
 * is what fences out the original worker should it ever finish.
 */
@Component
@RequiredArgsConstructor
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private static final String REAP_SQL = """
            WITH expired AS (
                SELECT id, locked_by AS old_lock
                FROM notification
                WHERE status = 'PROCESSING'
                  AND locked_until < :now
                ORDER BY locked_until
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            UPDATE notification n
            SET status          = CASE WHEN n.attempt_count >= n.max_attempts THEN 'FAILED' ELSE 'RETRYING' END,
                failure_reason  = CASE WHEN n.attempt_count >= n.max_attempts THEN :leaseExpired ELSE n.failure_reason END,
                last_error_code = :leaseExpired,
                next_attempt_at = :now,
                locked_by       = NULL,
                locked_until    = NULL,
                updated_at      = :now
            FROM expired
            WHERE n.id = expired.id
            RETURNING n.id, n.tenant_id, n.attempt_count, n.status, expired.old_lock
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DispatchAuditWriter auditWriter;
    private final Clock clock;

    /** @return number of rows recovered */
    @Transactional
    public int reap() {
        Instant now = clock.instant();

        List<Reaped> reaped = jdbc.query(REAP_SQL, new MapSqlParameterSource()
                        .addValue("now", SqlTime.ts(now), Types.TIMESTAMP)
                        .addValue("batchSize", DispatchConstants.REAPER_BATCH_SIZE)
                        .addValue("leaseExpired", DispatchConstants.ERROR_LEASE_EXPIRED),
                (rs, i) -> new Reaped(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getInt("attempt_count"),
                        NotificationStatus.valueOf(rs.getString("status")),
                        rs.getString("old_lock")));

        if (reaped.isEmpty()) {
            return 0;
        }

        List<DispatchAuditWriter.Attempt> attempts = new ArrayList<>(reaped.size());
        List<DispatchAuditWriter.Event> events = new ArrayList<>(reaped.size());
        for (Reaped r : reaped) {
            attempts.add(new DispatchAuditWriter.Attempt(r.id, r.attemptCount, now, now, AttemptOutcome.ABANDONED,
                    DispatchConstants.ERROR_LEASE_EXPIRED, "Lease " + r.oldLock + " expired", null, 0));
            events.add(new DispatchAuditWriter.Event(r.id, r.tenantId, NotificationStatus.PROCESSING, r.newStatus,
                    r.newStatus == NotificationStatus.FAILED
                            ? DispatchConstants.REASON_LEASE_EXPIRED_EXHAUSTED
                            : DispatchConstants.REASON_LEASE_EXPIRED,
                    EventActors.REAPER, now));
        }
        auditWriter.attempts(attempts);
        auditWriter.events(events);

        log.warn("Reaped {} notification(s) with expired leases", reaped.size());
        return reaped.size();
    }

    private record Reaped(UUID id, UUID tenantId, int attemptCount, NotificationStatus newStatus, String oldLock) {
    }
}

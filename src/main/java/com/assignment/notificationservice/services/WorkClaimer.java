package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.DispatchConstants;
import com.assignment.notificationservice.constants.EventActors;
import com.assignment.notificationservice.dtos.ClaimedNotification;
import com.assignment.notificationservice.dtos.TenantWithWeight;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.utils.SqlTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Claims due rows straight out of the notification table — the table <em>is</em> the queue.
 *
 * <p>One statement selects due rows with {@code FOR UPDATE SKIP LOCKED}, flips them to
 * PROCESSING under a lease and returns them. Concurrent claimers skip each other's locked
 * rows instead of waiting, so they never block, never deadlock and never receive the same row.
 */
@Component
@RequiredArgsConstructor
public class WorkClaimer {

    private static final String CLAIM_SQL = """
            WITH due AS (
                SELECT id, status AS old_status
                FROM notification
                WHERE tenant_id = :tenantId
                  AND channel = :channel
                  AND status IN ('SCHEDULED', 'PENDING', 'RETRYING')
                  AND next_attempt_at <= :now
                ORDER BY next_attempt_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            UPDATE notification n
            SET status        = 'PROCESSING',
                locked_by     = :lockToken,
                locked_until  = :lockUntil,
                attempt_count = n.attempt_count + 1,
                updated_at    = :now
            FROM due, tenant t
            WHERE n.id = due.id
              AND t.id = n.tenant_id
            RETURNING n.id, n.tenant_id, t.slug, n.channel, n.recipient, n.subject, n.body,
                      n.attempt_count, n.max_attempts, due.old_status
            """;

    /** Ordered by tenant ID so the fair selector's rotation sees a stable sequence. */
    private static final String TENANTS_WITH_DUE_WORK_SQL = """
            SELECT n.tenant_id, t.weight
            FROM notification n
            JOIN tenant t ON t.id = n.tenant_id
            WHERE n.status IN ('SCHEDULED', 'PENDING', 'RETRYING')
              AND n.next_attempt_at <= :now
              AND t.status = 'ACTIVE'
            GROUP BY n.tenant_id, t.weight
            ORDER BY n.tenant_id
            """;

    /** Undo of a claim whose worker never started. Fenced like any outcome write. */
    private static final String RELEASE_SQL = """
            UPDATE notification
            SET status        = 'PENDING',
                attempt_count = attempt_count - 1,
                locked_by     = NULL,
                locked_until  = NULL,
                updated_at    = :now
            WHERE id = :id
              AND status = 'PROCESSING'
              AND locked_by = :lockToken
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DispatchAuditWriter auditWriter;
    private final Clock clock;

    /** Identifies this dispatcher instance inside lease tokens (useful when reading locked_by). */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * A token unique to one claim call. Fencing on a per-instance ID would let a stale worker
     * overwrite a row that this same instance re-claimed after the reaper recovered it.
     */
    public String newLockToken() {
        return instanceId + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Claims up to {@code batchSize} due rows for one tenant and channel, and writes their
     * audit events in the same transaction.
     */
    @Transactional
    public List<ClaimedNotification> claim(UUID tenantId, Channel channel, int batchSize,
                                           String lockToken, Duration leaseDuration) {
        if (batchSize <= 0) {
            return List.of();
        }
        Instant now = clock.instant();

        List<ClaimedNotification> claimed = jdbc.query(CLAIM_SQL, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("channel", channel.name())
                        .addValue("now", SqlTime.ts(now), Types.TIMESTAMP)
                        .addValue("batchSize", batchSize)
                        .addValue("lockToken", lockToken)
                        .addValue("lockUntil", SqlTime.ts(now.plus(leaseDuration)), Types.TIMESTAMP),
                (rs, i) -> new ClaimedNotification(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("slug"),
                        Channel.valueOf(rs.getString("channel")),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        NotificationStatus.valueOf(rs.getString("old_status")),
                        lockToken));

        List<DispatchAuditWriter.Event> events = new ArrayList<>(claimed.size() + 1);
        for (ClaimedNotification n : claimed) {
            NotificationStatus from = n.previousStatus();
            if (from == NotificationStatus.SCHEDULED) {
                // SCHEDULED → PROCESSING is not a legal transition; the row passes through
                // PENDING the moment its scheduled time arrives. Record both steps.
                events.add(new DispatchAuditWriter.Event(n.id(), n.tenantId(), NotificationStatus.SCHEDULED,
                        NotificationStatus.PENDING, DispatchConstants.REASON_SCHEDULE_DUE, EventActors.DISPATCHER, now));
                from = NotificationStatus.PENDING;
            }
            events.add(new DispatchAuditWriter.Event(n.id(), n.tenantId(), from, NotificationStatus.PROCESSING,
                    DispatchConstants.REASON_CLAIMED, EventActors.DISPATCHER, now));
        }
        auditWriter.events(events);

        return claimed;
    }

    /**
     * Returns a claimed row to PENDING without consuming an attempt — used when the worker
     * pool rejects the task. Fenced on the lease token.
     *
     * @return false if the row no longer holds this lease (nothing changed)
     */
    @Transactional
    public boolean releaseToPending(ClaimedNotification n) {
        Instant now = clock.instant();
        int updated = jdbc.update(RELEASE_SQL, new MapSqlParameterSource()
                .addValue("id", n.id())
                .addValue("lockToken", n.lockToken())
                .addValue("now", SqlTime.ts(now), Types.TIMESTAMP));
        if (updated == 0) {
            return false;
        }
        auditWriter.event(new DispatchAuditWriter.Event(n.id(), n.tenantId(), NotificationStatus.PROCESSING,
                NotificationStatus.PENDING, DispatchConstants.REASON_POOL_REJECTED, EventActors.DISPATCHER, now));
        return true;
    }

    /** Active tenants with at least one row due now, in tenant-ID order. */
    @Transactional(readOnly = true)
    public List<TenantWithWeight> findTenantsWithDueWork(Instant now) {
        return jdbc.query(TENANTS_WITH_DUE_WORK_SQL,
                new MapSqlParameterSource("now", SqlTime.ts(now)),
                (rs, i) -> new TenantWithWeight(rs.getObject("tenant_id", UUID.class), rs.getInt("weight")));
    }
}

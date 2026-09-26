package com.assignment.notificationservice.models;

import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The central entity: both the domain object and the queue row.
 *
 * <p>There is no separate queue table. The dispatcher claims rows straight out of this table
 * with {@code FOR UPDATE SKIP LOCKED}, driven by {@code status} and {@code nextAttemptAt}.
 *
 * <p>{@code subject} and {@code body} are rendered at accept time and frozen, so a template
 * edit can never change what an already-queued notification sends.
 */
@Entity
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notif_idempotency",
                columnNames = {"tenant_id", "idempotency_key"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // ---- ownership ----

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Column(name = "recipient", nullable = false, length = 500)
    private String recipient;

    // ---- idempotency ----

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    // ---- template snapshot ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private Template template;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", columnDefinition = "jsonb")
    private String variables;

    // ---- state ----

    /**
     * Do not call {@code setStatus} directly. Every status change must go through
     * {@link com.assignment.notificationservice.services.NotificationStateMachine#transition}
     * so the transition is validated and an audit event is written in the same transaction.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    // ---- scheduling + queue ----

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    // ---- retry ----

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    // ---- lease ----

    @Column(name = "locked_by", length = 50)
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    // ---- outcome ----

    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "sent_at")
    private Instant sentAt;

    // ---- audit ----

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Notification(Tenant tenant, Channel channel, String recipient,
                        String idempotencyKey, String requestHash,
                        String body, NotificationStatus status,
                        Instant nextAttemptAt, Instant now) {
        this.id = UUID.randomUUID();
        this.tenant = tenant;
        this.channel = channel;
        this.recipient = recipient;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.body = body;
        this.status = status;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = now;
        this.updatedAt = now;
    }
}

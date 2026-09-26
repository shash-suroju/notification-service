package com.assignment.notificationservice.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable audit row: one per status transition, written in the same transaction
 * as the status change. The notification row holds current state; this table holds history.
 *
 * <p>{@code tenantId} is denormalised (not a FK relation) so reporting queries can filter
 * by tenant without joining through notification.
 */
@Entity
@Table(name = "notification_event")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEvent {

    /** Allowed values for {@link #actor}, enforced by the {@code ck_event_actor} constraint. */
    public static final String ACTOR_API = "API";
    public static final String ACTOR_DISPATCHER = "DISPATCHER";
    public static final String ACTOR_REAPER = "REAPER";
    public static final String ACTOR_ADMIN = "ADMIN";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private NotificationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private NotificationStatus toStatus;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "actor", nullable = false, length = 20)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public NotificationEvent(Notification notification, UUID tenantId,
                             NotificationStatus fromStatus, NotificationStatus toStatus,
                             String reason, String actor, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.notification = notification;
        this.tenantId = tenantId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }
}

package com.assignment.notificationservice.models;

import com.assignment.notificationservice.models.enums.AttemptOutcome;
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

import java.time.Instant;
import java.util.UUID;

/**
 * One provider call for one notification. The unique constraint on
 * (notification_id, attempt_no) makes a duplicate attempt row structurally impossible.
 */
@Entity
@Table(name = "delivery_attempt",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_attempt",
                columnNames = {"notification_id", "attempt_no"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 30)
    private AttemptOutcome outcome;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "latency_ms")
    private Long latencyMs;

    public DeliveryAttempt(Notification notification, int attemptNo, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.notification = notification;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
    }
}

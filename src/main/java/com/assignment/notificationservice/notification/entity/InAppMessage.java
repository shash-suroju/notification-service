package com.assignment.notificationservice.notification.entity;

import com.assignment.notificationservice.tenant.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The inbox row produced by the IN_APP channel.
 * {@code UNIQUE(notification_id)} is the last idempotency layer: one inbox entry per
 * notification, even if two workers both deliver it.
 */
@Entity
@Table(name = "in_app_message",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_inapp_notification",
                columnNames = {"notification_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InAppMessage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "recipient", nullable = false, length = 500)
    private String recipient;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public InAppMessage(Tenant tenant, String recipient, Notification notification,
                        String title, String body, Instant now) {
        this.id = UUID.randomUUID();
        this.tenant = tenant;
        this.recipient = recipient;
        this.notification = notification;
        this.title = title;
        this.body = body;
        this.createdAt = now;
    }
}

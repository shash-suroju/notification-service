package com.assignment.notificationservice.template.entity;

import com.assignment.notificationservice.common.Channel;
import com.assignment.notificationservice.tenant.entity.Tenant;
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
 * A versioned message template. Editing a template creates a new version rather than
 * mutating the existing one, so notifications already queued keep their snapshot.
 */
@Entity
@Table(name = "template",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_template_version",
                columnNames = {"tenant_id", "code", "channel", "version"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Template {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Template(Tenant tenant, String code, Channel channel, int version,
                    String subject, String body, Instant now) {
        this.id = UUID.randomUUID();
        this.tenant = tenant;
        this.code = code;
        this.channel = channel;
        this.version = version;
        this.subject = subject;
        this.body = body;
        this.createdAt = now;
    }
}

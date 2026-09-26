package com.assignment.notificationservice.models;

import com.assignment.notificationservice.models.enums.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer of the platform. Owns templates, API keys, channel configs and notifications.
 *
 * <p>Carries an {@code @Version} column: tenant updates are rare and made by admins, so a
 * concurrent edit should fail loudly with 409 rather than silently last-write-wins.
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status = TenantStatus.ACTIVE;

    @Column(name = "rate_limit_per_sec", nullable = false)
    private int rateLimitPerSec = 100;

    @Column(name = "burst", nullable = false)
    private int burst = 200;

    @Column(name = "weight", nullable = false)
    private int weight = 1;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Managed by Hibernate only — no setter. */
    @Version
    @Setter(AccessLevel.NONE)
    @Column(name = "version", nullable = false)
    private Long version;

    public Tenant(String name, String slug, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.slug = slug;
        this.createdAt = now;
        this.updatedAt = now;
    }
}

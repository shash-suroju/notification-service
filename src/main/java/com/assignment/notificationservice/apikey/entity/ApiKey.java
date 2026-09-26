package com.assignment.notificationservice.apikey.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A machine credential for the send API.
 *
 * <p>The plaintext key ({@code ntfy_<prefix>_<random>}) is shown once at creation and never
 * stored. {@code prefix} is the indexed lookup handle; {@code keyHash} is the SHA-256 of the
 * full key, compared on every request.
 */
@Entity
@Table(name = "api_key")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "prefix", nullable = false, length = 8)
    private String prefix;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "name", length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public ApiKey(Tenant tenant, String prefix, String keyHash, String name, Instant now) {
        this.id = UUID.randomUUID();
        this.tenant = tenant;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.name = name;
        this.createdAt = now;
    }
}

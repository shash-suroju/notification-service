package com.assignment.notificationservice.models;

import com.assignment.notificationservice.models.enums.Channel;
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

import java.util.UUID;

/**
 * Per-tenant, per-channel switch and provider settings.
 * {@code settings} holds channel-specific JSON, e.g. {@code {"fromAddress": "..."}}.
 */
@Entity
@Table(name = "channel_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_channelconfig_tenant_channel",
                columnNames = {"tenant_id", "channel"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChannelConfig {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private String settings = "{}";

    public ChannelConfig(Tenant tenant, Channel channel, boolean enabled) {
        this.id = UUID.randomUUID();
        this.tenant = tenant;
        this.channel = channel;
        this.enabled = enabled;
    }
}

package com.assignment.notificationservice.models;

import com.assignment.notificationservice.models.enums.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Platform-wide ceiling for a channel, shared across all tenants.
 * This is the second layer of the two-layer token bucket check.
 */
@Entity
@Table(name = "global_channel_limit")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GlobalChannelLimit {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Column(name = "rate_per_sec", nullable = false)
    private int ratePerSec = 500;

    @Column(name = "burst", nullable = false)
    private int burst = 1000;
}

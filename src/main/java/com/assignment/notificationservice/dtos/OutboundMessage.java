package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;

import java.util.UUID;

/**
 * What a {@code ChannelSender} delivers. {@code idempotencyKey} is the notification ID, so a
 * provider receiving the same message twice (e.g. after a lease was reaped) can deduplicate.
 */
public record OutboundMessage(
        UUID notificationId,
        String idempotencyKey,
        String recipient,
        String subject,
        String body,
        Channel channel,
        String tenantSlug
) {
}

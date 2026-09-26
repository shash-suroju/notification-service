package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;

import java.util.UUID;

/**
 * A row this dispatcher now holds a lease on, as returned by the claim query.
 *
 * @param attemptCount   already incremented by the claim — this is the attempt being made
 * @param previousStatus the status before the claim (SCHEDULED, PENDING or RETRYING)
 * @param lockToken      unique per claim; every outcome write is fenced on it
 */
public record ClaimedNotification(
        UUID id,
        UUID tenantId,
        String tenantSlug,
        Channel channel,
        String recipient,
        String subject,
        String body,
        int attemptCount,
        int maxAttempts,
        NotificationStatus previousStatus,
        String lockToken
) {
}

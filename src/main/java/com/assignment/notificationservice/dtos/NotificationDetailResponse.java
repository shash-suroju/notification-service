package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Full view of one notification: the rendered snapshot, every attempt, and the audit timeline. */
public record NotificationDetailResponse(
        UUID id,
        NotificationStatus status,
        Channel channel,
        String recipient,
        String templateCode,
        Integer templateVersion,
        String subject,
        String body,
        Map<String, String> variables,
        Instant scheduledAt,
        int attemptCount,
        int maxAttempts,
        String lastErrorCode,
        String failureReason,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt,
        List<DeliveryAttemptDto> attempts,
        List<NotificationEventDto> timeline
) {
}

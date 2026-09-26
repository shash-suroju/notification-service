package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public record SendNotificationResponse(
        UUID id,
        NotificationStatus status,
        Channel channel,
        String recipient,
        String templateCode,
        Integer templateVersion,
        Instant scheduledAt,
        Instant createdAt
) {
}

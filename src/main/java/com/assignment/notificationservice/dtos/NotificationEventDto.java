package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.NotificationStatus;

import java.time.Instant;

/** One audit-trail entry. {@code fromStatus} is null for the creating event. */
public record NotificationEventDto(
        NotificationStatus fromStatus,
        NotificationStatus toStatus,
        String reason,
        String actor,
        Instant occurredAt
) {
}

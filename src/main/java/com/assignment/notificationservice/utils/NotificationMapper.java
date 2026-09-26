package com.assignment.notificationservice.utils;

import com.assignment.notificationservice.dtos.DeliveryAttemptDto;
import com.assignment.notificationservice.dtos.NotificationEventDto;
import com.assignment.notificationservice.dtos.SendNotificationResponse;
import com.assignment.notificationservice.models.DeliveryAttempt;
import com.assignment.notificationservice.models.Notification;
import com.assignment.notificationservice.models.NotificationEvent;

/**
 * Entity → DTO mapping shared by the send API and the tenant console.
 *
 * <p>Reads {@code notification.template}, so callers must pass a notification whose template
 * was fetched (the repository's entity graphs do this) or map inside a transaction —
 * open-in-view is off, so a lazy proxy here would throw.
 */
public final class NotificationMapper {

    private NotificationMapper() {
    }

    public static SendNotificationResponse toSendResponse(Notification n) {
        return new SendNotificationResponse(
                n.getId(),
                n.getStatus(),
                n.getChannel(),
                n.getRecipient(),
                n.getTemplate() != null ? n.getTemplate().getCode() : null,
                n.getTemplateVersion(),
                n.getScheduledAt(),
                n.getCreatedAt());
    }

    public static DeliveryAttemptDto toDto(DeliveryAttempt a) {
        return new DeliveryAttemptDto(
                a.getId(), a.getAttemptNo(), a.getOutcome(),
                a.getErrorCode(), a.getErrorMessage(), a.getProviderMessageId(),
                a.getLatencyMs(), a.getStartedAt(), a.getFinishedAt());
    }

    public static NotificationEventDto toDto(NotificationEvent e) {
        return new NotificationEventDto(
                e.getFromStatus(), e.getToStatus(), e.getReason(), e.getActor(), e.getOccurredAt());
    }
}

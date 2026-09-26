package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/notifications}.
 * {@code variables} may be null (no variables); {@code scheduledAt} null means send now.
 */
public record SendNotificationRequest(
        @NotNull Channel channel,
        @NotBlank @Size(max = 500) String recipient,
        @NotBlank @Size(max = 100) String templateCode,
        Map<String, String> variables,
        Instant scheduledAt
) {
}

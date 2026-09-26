package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record TemplateResponse(
        UUID id,
        String code,
        Channel channel,
        int version,
        String subject,
        String body,
        boolean active,
        Set<String> requiredVariables,
        Instant createdAt
) {
}

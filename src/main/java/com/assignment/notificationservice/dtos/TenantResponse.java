package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.TenantStatus;

import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String slug,
        TenantStatus status,
        int rateLimitPerSec,
        int burst,
        int weight,
        int maxAttempts,
        Instant createdAt,
        Instant updatedAt
) {
}

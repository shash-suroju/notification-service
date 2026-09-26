package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.ApiKeyStatus;

import java.time.Instant;
import java.util.UUID;

/** List view of a key. Deliberately has no raw key or hash. */
public record ApiKeyResponse(
        UUID id,
        String prefix,
        String name,
        ApiKeyStatus status,
        Instant createdAt,
        Instant lastUsedAt
) {
}

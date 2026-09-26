package com.assignment.notificationservice.dtos;

import java.time.Instant;
import java.util.UUID;

/**
 * Returned only from key creation. {@code rawKey} is shown exactly once — only its SHA-256
 * is stored, so it can never be retrieved again.
 */
public record ApiKeyCreateResponse(
        UUID id,
        String prefix,
        String rawKey,
        String name,
        Instant createdAt
) {
}

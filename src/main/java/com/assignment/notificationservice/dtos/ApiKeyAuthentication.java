package com.assignment.notificationservice.dtos;

import java.util.UUID;

/** Result of a successful API key check. Internal only — never serialised to clients. */
public record ApiKeyAuthentication(
        UUID tenantId,
        UUID apiKeyId
) {
}

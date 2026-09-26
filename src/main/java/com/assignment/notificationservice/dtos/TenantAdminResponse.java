package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Role;

import java.time.Instant;
import java.util.UUID;

/** Deliberately has no password or hash field. */
public record TenantAdminResponse(
        UUID id,
        String username,
        Role role,
        UUID tenantId,
        Instant createdAt
) {
}

package com.assignment.notificationservice.dtos;

import java.util.UUID;

/** One tenant's slot in a tick: claim up to {@code quantum} rows per channel. */
public record TenantWork(UUID tenantId, int quantum, int weight) {
}

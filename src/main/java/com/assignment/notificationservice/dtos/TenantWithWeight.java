package com.assignment.notificationservice.dtos;

import java.util.UUID;

/** A tenant that has due work this tick, with its fairness weight. */
public record TenantWithWeight(UUID tenantId, int weight) {
}

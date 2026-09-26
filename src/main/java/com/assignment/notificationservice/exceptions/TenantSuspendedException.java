package com.assignment.notificationservice.exceptions;

import java.util.UUID;

/** A suspended tenant may not submit notifications. Mapped to 403. */
public class TenantSuspendedException extends RuntimeException {

    public TenantSuspendedException(UUID tenantId) {
        super("Tenant " + tenantId + " is suspended");
    }
}

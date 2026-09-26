package com.assignment.notificationservice.support;

import java.util.UUID;

/** Credentials for a tenant created by {@code BaseIntegrationTest.setupTenant}. */
public record TestTenant(UUID id, String slug, String username, String password) {
}

package com.assignment.notificationservice.dtos;

public record PlatformSettingsResponse(
        int maxTenantRatePerSec,
        int defaultMaxAttempts
) {
}

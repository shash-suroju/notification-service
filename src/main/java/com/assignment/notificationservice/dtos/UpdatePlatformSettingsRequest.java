package com.assignment.notificationservice.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Partial update: null fields are left unchanged. */
public record UpdatePlatformSettingsRequest(
        @Min(1) Integer maxTenantRatePerSec,
        @Min(1) @Max(20) Integer defaultMaxAttempts
) {
}

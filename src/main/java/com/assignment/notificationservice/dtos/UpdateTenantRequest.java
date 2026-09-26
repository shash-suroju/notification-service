package com.assignment.notificationservice.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: null fields are left unchanged. The slug is immutable. */
public record UpdateTenantRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Min(1) Integer rateLimitPerSec,
        @Min(1) Integer burst,
        @Min(1) @Max(10) Integer weight,
        @Min(1) @Max(20) Integer maxAttempts
) {
}

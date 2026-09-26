package com.assignment.notificationservice.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code weight} defaults to 1 and {@code maxAttempts} to the platform's
 * {@code default_max_attempts} setting when omitted. Cross-field rules (burst ≥ rate,
 * rate ≤ platform cap) are checked by the service, which knows the current cap.
 */
public record CreateTenantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug must be lowercase alphanumeric with hyphens only")
        String slug,
        @NotNull @Min(1) Integer rateLimitPerSec,
        @NotNull @Min(1) Integer burst,
        @Min(1) @Max(10) Integer weight,
        @Min(1) @Max(20) Integer maxAttempts
) {
}

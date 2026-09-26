package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code subject} is required for EMAIL (checked in the service), optional otherwise. */
public record CreateTemplateRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9_]+$") String code,
        @NotNull Channel channel,
        @Size(max = 500) String subject,
        @NotBlank @Size(max = 10000) String body
) {
}

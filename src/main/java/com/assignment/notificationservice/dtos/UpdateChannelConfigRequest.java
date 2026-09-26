package com.assignment.notificationservice.dtos;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** {@code settings} is optional; when omitted the stored settings are left unchanged. */
public record UpdateChannelConfigRequest(
        @NotNull Boolean enabled,
        Map<String, String> settings
) {
}

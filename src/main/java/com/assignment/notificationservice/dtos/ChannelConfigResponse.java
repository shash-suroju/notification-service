package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;

import java.util.Map;
import java.util.UUID;

/** {@code id} is null for a channel the tenant has never configured (reported as disabled). */
public record ChannelConfigResponse(
        UUID id,
        Channel channel,
        boolean enabled,
        Map<String, String> settings
) {
}

package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;

public record GlobalChannelLimitResponse(
        Channel channel,
        int ratePerSec,
        int burst
) {
}

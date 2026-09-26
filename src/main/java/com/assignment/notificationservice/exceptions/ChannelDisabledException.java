package com.assignment.notificationservice.exceptions;

import com.assignment.notificationservice.models.enums.Channel;
import lombok.Getter;

import java.util.UUID;

/** The tenant has not enabled this channel. Mapped to 400. */
@Getter
public class ChannelDisabledException extends RuntimeException {

    private final Channel channel;

    public ChannelDisabledException(Channel channel, UUID tenantId) {
        super("Channel " + channel + " is not enabled for this tenant");
        this.channel = channel;
    }
}

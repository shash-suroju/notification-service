package com.assignment.notificationservice.services;

import com.assignment.notificationservice.dtos.OutboundMessage;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;

/**
 * Strategy per channel. The dispatcher only ever sees this interface, so adding a channel
 * means adding one implementation — no changes to claiming, workers or outcome recording.
 *
 * <p>Implementations report failures as {@link SendResult} values rather than exceptions.
 * They are called with no database transaction open.
 */
public interface ChannelSender {

    Channel channel();

    SendResult send(OutboundMessage msg);
}

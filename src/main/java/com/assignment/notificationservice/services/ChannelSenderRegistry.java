package com.assignment.notificationservice.services;

import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Indexes every {@link ChannelSender} bean by channel. Fails at startup — not at the first
 * send — if a channel has no sender or two senders claim the same channel.
 */
@Component
public class ChannelSenderRegistry {

    private final Map<Channel, ChannelSender> senders = new EnumMap<>(Channel.class);

    public ChannelSenderRegistry(List<ChannelSender> allSenders) {
        for (ChannelSender sender : allSenders) {
            ChannelSender previous = senders.putIfAbsent(sender.channel(), sender);
            if (previous != null) {
                throw new IllegalStateException("Two senders registered for " + sender.channel() + ": "
                        + previous.getClass().getSimpleName() + " and " + sender.getClass().getSimpleName());
            }
        }
        for (Channel channel : Channel.values()) {
            if (!senders.containsKey(channel)) {
                throw new IllegalStateException("No sender registered for channel " + channel);
            }
        }
    }

    public ChannelSender get(Channel channel) {
        ChannelSender sender = senders.get(channel);
        if (sender == null) {
            throw new IllegalArgumentException("No sender registered for channel: " + channel);
        }
        return sender;
    }
}

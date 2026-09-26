package com.assignment.notificationservice.exceptions;

import com.assignment.notificationservice.models.enums.Channel;
import lombok.Getter;

/**
 * Recipient does not match the channel's expected format. Mapped to 400.
 * The recipient is kept for logging but deliberately not echoed in the API response.
 */
@Getter
public class InvalidRecipientException extends RuntimeException {

    private final Channel channel;
    private final String recipient;

    public InvalidRecipientException(Channel channel, String recipient, String message) {
        super(message);
        this.channel = channel;
        this.recipient = recipient;
    }
}

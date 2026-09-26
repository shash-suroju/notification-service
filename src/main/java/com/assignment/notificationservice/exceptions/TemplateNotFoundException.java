package com.assignment.notificationservice.exceptions;

import com.assignment.notificationservice.models.enums.Channel;

/**
 * No active template for (code, channel). Also covers a code that exists only for another
 * channel. Mapped to 400: the request is wrong, not the URL.
 */
public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException(String code, Channel channel) {
        super("No active template found with code '" + code + "' for channel " + channel);
    }
}

package com.assignment.notificationservice.models.enums;

import java.util.Locale;

/**
 * The delivery channels supported by the platform.
 * Stored as the enum name in every {@code VARCHAR(20)} channel column.
 */
public enum Channel {
    EMAIL, SMS, PUSH, IN_APP;

    /**
     * Case-insensitive parse of a path variable ("email", "IN_APP").
     *
     * @throws IllegalArgumentException with a client-safe message (mapped to 400)
     */
    public static Channel fromPath(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown channel: " + raw);
        }
    }
}

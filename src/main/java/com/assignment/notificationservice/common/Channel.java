package com.assignment.notificationservice.common;

/**
 * The delivery channels supported by the platform.
 * Stored as the enum name in every {@code VARCHAR(20)} channel column.
 */
public enum Channel {
    EMAIL, SMS, PUSH, IN_APP
}

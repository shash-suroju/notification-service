package com.assignment.notificationservice.exceptions;

/** {@code scheduledAt} is in the past or beyond the scheduling horizon. Mapped to 400. */
public class InvalidScheduleTimeException extends RuntimeException {

    public InvalidScheduleTimeException(String message) {
        super(message);
    }
}

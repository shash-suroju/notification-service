package com.assignment.notificationservice.exceptions;

/** A rendered SMS body exceeds the concatenated-segment limit. Mapped to 400. */
public class SmsBodyTooLongException extends RuntimeException {

    public SmsBodyTooLongException(int actual, int max) {
        super("SMS body too long: " + actual + " chars (max " + max + ")");
    }
}

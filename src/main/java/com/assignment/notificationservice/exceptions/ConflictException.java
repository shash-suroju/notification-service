package com.assignment.notificationservice.exceptions;

/**
 * The request conflicts with current state — a duplicate slug, a reused idempotency key,
 * or an operation that is not valid for the resource's current status.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

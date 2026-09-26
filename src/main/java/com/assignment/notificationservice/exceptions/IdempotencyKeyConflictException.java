package com.assignment.notificationservice.exceptions;

/** Same idempotency key, different payload — a client bug, not a retry. Mapped to 422. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String key) {
        super("Idempotency key '" + key + "' already used with a different payload");
    }
}

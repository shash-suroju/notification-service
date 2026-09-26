package com.assignment.notificationservice.dtos;

/**
 * Outcome of one provider call. Sealed so every {@code switch} over it is checked for
 * exhaustiveness by the compiler — a new outcome cannot be silently unhandled.
 */
public sealed interface SendResult {

    record Success(String providerMessageId) implements SendResult {
    }

    /** Worth retrying: timeouts, throttling, 5xx. */
    record TransientFailure(String errorCode, String message) implements SendResult {
    }

    /**
     * Reported as permanent by the provider. {@code FailureClassifier} still has the final
     * say: an error code it does not recognise is retried rather than failed outright.
     */
    record PermanentFailure(String errorCode, String message) implements SendResult {
    }
}

package com.assignment.notificationservice.utils;

import com.assignment.notificationservice.dtos.SendResult;

import java.util.Set;

/**
 * Decides whether a failed send is worth retrying.
 *
 * <p>Only error codes known to be permanent fail immediately. An unknown code is treated as
 * transient even if the provider labelled it permanent: retrying once too often is cheap,
 * permanently dropping a deliverable message is not.
 */
public final class FailureClassifier {

    private FailureClassifier() {
    }

    private static final Set<String> PERMANENT_CODES = Set.of(
            "INVALID_RECIPIENT",
            "INVALID_TOKEN",
            "UNSUBSCRIBED",
            "BAD_PAYLOAD",
            "BLOCKED",
            "CARRIER_REJECTED",
            "SPAM_DETECTED");

    public static boolean isTransient(SendResult result) {
        return switch (result) {
            case SendResult.Success s -> false;
            case SendResult.TransientFailure t -> true;
            case SendResult.PermanentFailure p -> !PERMANENT_CODES.contains(p.errorCode());
        };
    }

    public static boolean isPermanent(SendResult result) {
        return result instanceof SendResult.PermanentFailure p && PERMANENT_CODES.contains(p.errorCode());
    }
}

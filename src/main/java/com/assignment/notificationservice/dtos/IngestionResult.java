package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.Notification;

/**
 * Internal result of a submit. {@code created} decides the status code:
 * true → 202 Accepted (new row), false → 200 OK (idempotent replay of an existing row).
 */
public record IngestionResult(
        Notification notification,
        boolean created
) {

    public static IngestionResult created(Notification n) {
        return new IngestionResult(n, true);
    }

    public static IngestionResult duplicate(Notification n) {
        return new IngestionResult(n, false);
    }
}

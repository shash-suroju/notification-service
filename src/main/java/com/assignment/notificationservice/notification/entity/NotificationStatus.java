package com.assignment.notificationservice.notification.entity;

/**
 * The seven states a notification can occupy.
 *
 * <p>SENT, FAILED and CANCELLED are terminal (except FAILED, which an admin may
 * manually re-queue to PENDING). Legal transitions are enforced by
 * {@link com.assignment.notificationservice.notification.statemachine.NotificationStateMachine}.
 */
public enum NotificationStatus {
    SCHEDULED, PENDING, PROCESSING, RETRYING, SENT, FAILED, CANCELLED
}

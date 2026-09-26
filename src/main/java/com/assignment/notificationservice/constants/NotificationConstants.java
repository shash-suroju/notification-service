package com.assignment.notificationservice.constants;

import java.time.Duration;

public final class NotificationConstants {

    private NotificationConstants() {
    }

    /** Required on every submit; scoped per tenant by {@code UNIQUE(tenant_id, idempotency_key)}. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;

    /** Name of the unique constraint that makes duplicate submits structurally impossible. */
    public static final String IDEMPOTENCY_CONSTRAINT = "uq_notif_idempotency";

    /** Furthest ahead a notification may be scheduled. */
    public static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(30);

    /** {@code NotificationEvent.reason} values written by the ingestion path. */
    public static final String REASON_IMMEDIATE_SUBMIT = "immediate_submit";
    public static final String REASON_SCHEDULED_SUBMIT = "scheduled_submit";
    public static final String REASON_CANCELLED_BY_USER = "cancelled_by_user";
}

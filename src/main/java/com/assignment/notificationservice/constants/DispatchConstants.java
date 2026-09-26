package com.assignment.notificationservice.constants;

public final class DispatchConstants {

    private DispatchConstants() {
    }

    // ---- NotificationEvent.reason values written by the dispatcher and reaper ----
    public static final String REASON_SCHEDULE_DUE = "schedule_due";
    public static final String REASON_CLAIMED = "claimed";
    public static final String REASON_POOL_REJECTED = "pool_rejected";
    public static final String REASON_PROVIDER_SUCCESS = "provider_success";
    public static final String REASON_RETRIES_EXHAUSTED = "retries_exhausted";
    public static final String REASON_TRANSIENT_FAILURE_PREFIX = "transient_failure:";
    public static final String REASON_PERMANENT_FAILURE_PREFIX = "permanent_failure:";
    public static final String REASON_LEASE_EXPIRED = "lease_expired";
    public static final String REASON_LEASE_EXPIRED_EXHAUSTED = "lease_expired_retries_exhausted";

    // ---- notification.failure_reason / error codes ----
    public static final String FAILURE_RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";
    public static final String FAILURE_PERMANENT_PREFIX = "PERMANENT_FAILURE: ";
    public static final String ERROR_LEASE_EXPIRED = "LEASE_EXPIRED";
    public static final String ERROR_SENDER_EXCEPTION = "SENDER_EXCEPTION";

    /** Upper bound on rows the reaper recovers per sweep, so one sweep stays short. */
    public static final int REAPER_BATCH_SIZE = 500;

    /** Column limits for error text written by the dispatcher (see V008 / V009). */
    public static final int MAX_ERROR_TEXT_LENGTH = 500;
}

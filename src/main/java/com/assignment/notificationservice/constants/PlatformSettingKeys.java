package com.assignment.notificationservice.constants;

/** Keys of the {@code platform_setting} table, with the values used when a row is missing. */
public final class PlatformSettingKeys {

    private PlatformSettingKeys() {
    }

    /** Ceiling for any tenant's {@code rateLimitPerSec}; checked on tenant create/update. */
    public static final String MAX_TENANT_RATE_PER_SEC = "max_tenant_rate_per_sec";
    public static final int DEFAULT_MAX_TENANT_RATE_PER_SEC = 1000;

    /** Retry budget given to a new tenant that does not specify {@code maxAttempts}. */
    public static final String DEFAULT_MAX_ATTEMPTS = "default_max_attempts";
    public static final int DEFAULT_DEFAULT_MAX_ATTEMPTS = 5;

    /** Weight given to a new tenant that does not specify one. */
    public static final int DEFAULT_TENANT_WEIGHT = 1;
}

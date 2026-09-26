package com.assignment.notificationservice.constants;

/**
 * Every URL the API exposes. Controllers map these and {@code SecurityConfig} authorises
 * them, so a path and its access rule can never drift apart.
 */
public final class ApiPaths {

    private ApiPaths() {
    }

    public static final String API_V1 = "/api/v1";

    /** Platform-admin console (PLATFORM_ADMIN, HTTP Basic). */
    public static final String ADMIN = API_V1 + "/admin";

    /** Tenant-admin console (TENANT_ADMIN, HTTP Basic). */
    public static final String TENANT = API_V1 + "/tenant";
    public static final String TENANT_TEMPLATES = TENANT + "/templates";
    public static final String TENANT_CHANNELS = TENANT + "/channels";
    public static final String TENANT_API_KEYS = TENANT + "/api-keys";

    /** Send API (tenant backends, X-API-Key). */
    public static final String NOTIFICATIONS = API_V1 + "/notifications";

    /** Suffix for "this path and everything below it" in security matchers. */
    public static final String ALL_BELOW = "/**";
}

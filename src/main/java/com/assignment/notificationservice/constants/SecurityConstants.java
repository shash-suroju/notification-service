package com.assignment.notificationservice.constants;

public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** Request header carrying a tenant's send-API key. */
    public static final String API_KEY_HEADER = "X-API-Key";

    /** Every issued key looks like {@code ntfy_<prefix>_<secret>}. */
    public static final String API_KEY_PREFIX = "ntfy_";
    public static final int API_KEY_LOOKUP_PREFIX_LENGTH = 8;
    public static final int API_KEY_SECRET_LENGTH = 32;
    public static final String API_KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** Role names as used by {@code hasRole(...)} (Spring adds the {@code ROLE_} prefix). */
    public static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String ROLE_TENANT_ADMIN = "TENANT_ADMIN";

    /** Prefix Spring Security expects on granted authorities for role checks. */
    public static final String AUTHORITY_ROLE_PREFIX = "ROLE_";
}

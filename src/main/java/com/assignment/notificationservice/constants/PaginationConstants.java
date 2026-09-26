package com.assignment.notificationservice.constants;

public final class PaginationConstants {

    private PaginationConstants() {
    }

    /** String forms so they can be used in {@code @RequestParam(defaultValue = ...)}. */
    public static final String DEFAULT_PAGE = "0";
    public static final String DEFAULT_PAGE_SIZE = "20";

    /** Larger requested sizes are silently capped to this. */
    public static final int MAX_PAGE_SIZE = 100;
}

package com.assignment.notificationservice.constants;

public final class TemplateConstants {

    private TemplateConstants() {
    }

    /** Max rendered SMS body: 3 concatenated 160-char segments. */
    public static final int SMS_MAX_LENGTH = 480;

    /** Matches {@code {{name}}}, tolerating inner whitespace: {@code {{ name }}}. */
    public static final String VARIABLE_REGEX = "\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}";
}

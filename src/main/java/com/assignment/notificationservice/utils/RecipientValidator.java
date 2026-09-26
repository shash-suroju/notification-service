package com.assignment.notificationservice.utils;

import com.assignment.notificationservice.exceptions.InvalidRecipientException;
import com.assignment.notificationservice.models.enums.Channel;

import java.util.regex.Pattern;

/**
 * Per-channel recipient format checks, run at accept time so a malformed address is
 * rejected with 400 instead of burning retry attempts against the provider later.
 */
public final class RecipientValidator {

    private RecipientValidator() {
    }

    /** RFC 5322, simplified — good enough to validate, not to parse. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    /** E.164: '+', then a non-zero country digit, up to 15 digits total. */
    private static final Pattern E164_PATTERN =
            Pattern.compile("^\\+[1-9]\\d{1,14}$");

    /** APNs (64 hex) or FCM (longer alphanumeric with : . _ -) device tokens. */
    private static final Pattern DEVICE_TOKEN_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_:.-]{32,256}$");

    private static final int MAX_USER_ID_LENGTH = 255;

    /** @throws InvalidRecipientException if {@code recipient} is blank or malformed for {@code channel} */
    public static void validate(Channel channel, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new InvalidRecipientException(channel, recipient, "Recipient cannot be blank");
        }

        switch (channel) {
            case EMAIL -> {
                if (!EMAIL_PATTERN.matcher(recipient).matches()) {
                    throw new InvalidRecipientException(channel, recipient, "Invalid email format");
                }
            }
            case SMS -> {
                if (!E164_PATTERN.matcher(recipient).matches()) {
                    throw new InvalidRecipientException(channel, recipient,
                            "Invalid phone number. Expected E.164 format: +<country><number>");
                }
            }
            case PUSH -> {
                if (!DEVICE_TOKEN_PATTERN.matcher(recipient).matches()) {
                    throw new InvalidRecipientException(channel, recipient, "Invalid device token format");
                }
            }
            case IN_APP -> {
                if (recipient.length() > MAX_USER_ID_LENGTH) {
                    throw new InvalidRecipientException(channel, recipient,
                            "User ID exceeds max length of " + MAX_USER_ID_LENGTH);
                }
            }
        }
    }
}

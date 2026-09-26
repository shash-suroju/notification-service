package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.exceptions.InvalidRecipientException;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.utils.RecipientValidator;
import org.junit.jupiter.api.Test;

import static com.assignment.notificationservice.models.enums.Channel.EMAIL;
import static com.assignment.notificationservice.models.enums.Channel.IN_APP;
import static com.assignment.notificationservice.models.enums.Channel.PUSH;
import static com.assignment.notificationservice.models.enums.Channel.SMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipientValidatorTest {

    // --- EMAIL ---
    @Test void email_valid()                 { valid(EMAIL, "user@example.com"); }
    @Test void email_valid_withPlus()        { valid(EMAIL, "user+tag@example.com"); }
    @Test void email_valid_subdomain()       { valid(EMAIL, "first.last@mail.example.co.uk"); }
    @Test void email_invalid_noAt()          { invalid(EMAIL, "userexample.com"); }
    @Test void email_invalid_noDomain()      { invalid(EMAIL, "user@"); }
    @Test void email_invalid_noTld()         { invalid(EMAIL, "user@example"); }
    @Test void email_invalid_blank()         { invalid(EMAIL, ""); }
    @Test void email_invalid_whitespace()    { invalid(EMAIL, "user @example.com"); }

    // --- SMS (E.164) ---
    @Test void sms_valid_us()                { valid(SMS, "+14155551234"); }
    @Test void sms_valid_india()             { valid(SMS, "+918184911672"); }
    @Test void sms_valid_max15Digits()       { valid(SMS, "+123456789012345"); }
    @Test void sms_invalid_noPlus()          { invalid(SMS, "14155551234"); }
    @Test void sms_invalid_tooShort()        { invalid(SMS, "+1"); }
    @Test void sms_invalid_tooLong()         { invalid(SMS, "+1234567890123456"); }
    @Test void sms_invalid_letters()         { invalid(SMS, "+1415abc1234"); }
    @Test void sms_invalid_startsWithZero()  { invalid(SMS, "+0123456789"); }
    @Test void sms_invalid_formatted()       { invalid(SMS, "+1 (415) 555-1234"); }

    // --- PUSH ---
    @Test void push_valid_apnsHexToken()     { valid(PUSH, "a".repeat(64)); }
    @Test void push_valid_fcmToken()         { valid(PUSH, "fcm:APA91b" + "x".repeat(140)); }
    @Test void push_invalid_tooShort()       { invalid(PUSH, "abc"); }
    @Test void push_invalid_tooLong()        { invalid(PUSH, "a".repeat(257)); }
    @Test void push_invalid_badChars()       { invalid(PUSH, "a".repeat(40) + "!"); }

    // --- IN_APP ---
    @Test void inApp_valid_userId()          { valid(IN_APP, "user-123"); }
    @Test void inApp_valid_maxLength()       { valid(IN_APP, "a".repeat(255)); }
    @Test void inApp_invalid_tooLong()       { invalid(IN_APP, "a".repeat(256)); }
    @Test void inApp_invalid_blank()         { invalid(IN_APP, "  "); }

    // --- null ---
    @Test void null_recipient_throws()       { invalid(EMAIL, null); }

    @Test
    void exceptionCarriesChannelAndRecipient() {
        assertThatThrownBy(() -> RecipientValidator.validate(SMS, "12345"))
                .isInstanceOfSatisfying(InvalidRecipientException.class, e -> {
                    assertThat(e.getChannel()).isEqualTo(SMS);
                    assertThat(e.getRecipient()).isEqualTo("12345");
                    assertThat(e.getMessage()).contains("E.164");
                });
    }

    private static void valid(Channel channel, String recipient) {
        assertThatCode(() -> RecipientValidator.validate(channel, recipient)).doesNotThrowAnyException();
    }

    private static void invalid(Channel channel, String recipient) {
        assertThatThrownBy(() -> RecipientValidator.validate(channel, recipient))
                .isInstanceOf(InvalidRecipientException.class);
    }
}

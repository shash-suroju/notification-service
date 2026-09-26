package com.assignment.notificationservice.support;

import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Deterministic senders for EMAIL, SMS and PUSH. IN_APP keeps the real {@code InAppSender},
 * which only writes to our own database and is fully testable as-is.
 *
 * <p>Deliberately <em>not</em> {@code @Primary}: the Mock*Senders are excluded by
 * {@code @Profile("!test")}, so there is nothing to override — and three primary beans of one
 * type would make every by-type injection of {@code ProgrammableSender} ambiguous. Inject them
 * by name ({@code @Qualifier("emailSender")}).
 */
@TestConfiguration
public class TestSenderConfig {

    @Bean
    public ProgrammableSender emailSender() {
        return new ProgrammableSender(Channel.EMAIL);
    }

    @Bean
    public ProgrammableSender smsSender() {
        return new ProgrammableSender(Channel.SMS);
    }

    @Bean
    public ProgrammableSender pushSender() {
        return new ProgrammableSender(Channel.PUSH);
    }
}

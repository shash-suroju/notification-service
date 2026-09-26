package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.configs.MockProviderProperties;
import com.assignment.notificationservice.dtos.OutboundMessage;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.MockEmailSender;
import com.assignment.notificationservice.services.MockPushSender;
import com.assignment.notificationservice.services.MockSmsSender;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The dev-profile simulated providers (not loaded in tests, so exercised directly here). */
class MockSenderTest {

    @Test
    void alwaysSucceeds_andDeduplicatesOnIdempotencyKey() {
        MockEmailSender sender = new MockEmailSender(properties(0.0, 0.0));
        OutboundMessage msg = message(Channel.EMAIL);

        SendResult first = sender.send(msg);
        SendResult second = sender.send(msg);

        assertThat(first).isInstanceOf(SendResult.Success.class);
        assertThat(second).isEqualTo(first);   // same provider message ID: delivered once
        assertThat(((SendResult.Success) first).providerMessageId()).startsWith("mock-email-");
    }

    @Test
    void permanentRateOne_alwaysFailsPermanently_withChannelSpecificCode() {
        assertThat(new MockEmailSender(properties(0.0, 1.0)).send(message(Channel.EMAIL)))
                .isEqualTo(new SendResult.PermanentFailure("INVALID_RECIPIENT", "Mailbox does not exist"));
        assertThat(new MockSmsSender(properties(0.0, 1.0)).send(message(Channel.SMS)))
                .isInstanceOfSatisfying(SendResult.PermanentFailure.class,
                        p -> assertThat(p.errorCode()).isEqualTo("CARRIER_REJECTED"));
        assertThat(new MockPushSender(properties(0.0, 1.0)).send(message(Channel.PUSH)))
                .isInstanceOfSatisfying(SendResult.PermanentFailure.class,
                        p -> assertThat(p.errorCode()).isEqualTo("INVALID_TOKEN"));
    }

    @Test
    void transientRateOne_alwaysFailsTransiently() {
        assertThat(new MockSmsSender(properties(1.0, 0.0)).send(message(Channel.SMS)))
                .isEqualTo(new SendResult.TransientFailure("THROTTLED", "Gateway throttled the request"));
    }

    @Test
    void failedSendIsNotRememberedAsSent() {
        MockPushSender failing = new MockPushSender(properties(1.0, 0.0));
        OutboundMessage msg = message(Channel.PUSH);

        assertThat(failing.send(msg)).isInstanceOf(SendResult.TransientFailure.class);
        assertThat(failing.send(msg)).isInstanceOf(SendResult.TransientFailure.class);
    }

    private static MockProviderProperties properties(double transientRate, double permanentRate) {
        MockProviderProperties props = new MockProviderProperties();
        MockProviderProperties.Provider p = new MockProviderProperties.Provider(0, transientRate, permanentRate);
        props.setEmail(p);
        props.setSms(p);
        props.setPush(p);
        return props;
    }

    private static OutboundMessage message(Channel channel) {
        UUID id = UUID.randomUUID();
        return new OutboundMessage(id, id.toString(), "r", "s", "b", channel, "acme");
    }
}

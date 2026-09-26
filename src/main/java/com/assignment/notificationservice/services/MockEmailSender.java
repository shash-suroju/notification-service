package com.assignment.notificationservice.services;

import com.assignment.notificationservice.configs.MockProviderProperties;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Simulated email provider. Replaced by {@code ProgrammableSender} in the test profile. */
@Component
@Profile("!test")
public class MockEmailSender extends AbstractMockSender {

    public MockEmailSender(MockProviderProperties properties) {
        super(properties.getEmail());
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    protected String providerIdPrefix() {
        return "mock-email-";
    }

    @Override
    protected SendResult.PermanentFailure permanentFailure() {
        return new SendResult.PermanentFailure("INVALID_RECIPIENT", "Mailbox does not exist");
    }

    @Override
    protected SendResult.TransientFailure transientFailure() {
        return new SendResult.TransientFailure("TIMEOUT", "Provider timed out");
    }
}

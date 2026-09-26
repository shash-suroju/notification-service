package com.assignment.notificationservice.services;

import com.assignment.notificationservice.configs.MockProviderProperties;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Simulated push service. Replaced by {@code ProgrammableSender} in the test profile. */
@Component
@Profile("!test")
public class MockPushSender extends AbstractMockSender {

    public MockPushSender(MockProviderProperties properties) {
        super(properties.getPush());
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    protected String providerIdPrefix() {
        return "mock-push-";
    }

    @Override
    protected SendResult.PermanentFailure permanentFailure() {
        return new SendResult.PermanentFailure("INVALID_TOKEN", "Device token is not registered");
    }

    @Override
    protected SendResult.TransientFailure transientFailure() {
        return new SendResult.TransientFailure("SERVICE_UNAVAILABLE", "Push service unavailable");
    }
}

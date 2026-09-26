package com.assignment.notificationservice.services;

import com.assignment.notificationservice.configs.MockProviderProperties;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Simulated SMS gateway. Replaced by {@code ProgrammableSender} in the test profile. */
@Component
@Profile("!test")
public class MockSmsSender extends AbstractMockSender {

    public MockSmsSender(MockProviderProperties properties) {
        super(properties.getSms());
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    protected String providerIdPrefix() {
        return "mock-sms-";
    }

    @Override
    protected SendResult.PermanentFailure permanentFailure() {
        return new SendResult.PermanentFailure("CARRIER_REJECTED", "Carrier rejected the message");
    }

    @Override
    protected SendResult.TransientFailure transientFailure() {
        return new SendResult.TransientFailure("THROTTLED", "Gateway throttled the request");
    }
}

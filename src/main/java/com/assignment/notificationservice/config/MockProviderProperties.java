package com.assignment.notificationservice.config;

import com.assignment.notificationservice.common.Channel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simulated latency and failure rates for the mock senders.
 * Bound from {@code notify.mock-providers}; all rates are zero in the test profile.
 *
 * <p>IN_APP has no entry: it writes to our own inbox table rather than calling a provider.
 */
@ConfigurationProperties(prefix = "notify.mock-providers")
@Getter
@Setter
public class MockProviderProperties {

    private Provider email = new Provider(50, 0.10, 0.02);
    private Provider sms = new Provider(80, 0.15, 0.02);
    private Provider push = new Provider(30, 0.05, 0.01);

    public Provider forChannel(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> sms;
            case PUSH -> push;
            case IN_APP -> throw new IllegalArgumentException(
                    "IN_APP is delivered locally and has no mock provider settings");
        };
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Provider {
        private long latencyMs;
        private double transientFailureRate;
        private double permanentFailureRate;
    }
}

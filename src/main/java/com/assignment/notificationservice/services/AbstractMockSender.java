package com.assignment.notificationservice.services;

import com.assignment.notificationservice.configs.MockProviderProperties;
import com.assignment.notificationservice.dtos.OutboundMessage;
import com.assignment.notificationservice.dtos.SendResult;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared behaviour of the simulated providers: configurable latency and failure rates, and
 * provider-side deduplication on the idempotency key (the notification ID) — so a message
 * re-sent after a lease was reaped is acknowledged, not delivered twice.
 */
public abstract class AbstractMockSender implements ChannelSender {

    private final MockProviderProperties.Provider config;
    private final ConcurrentHashMap<String, String> sentIds = new ConcurrentHashMap<>();

    protected AbstractMockSender(MockProviderProperties.Provider config) {
        this.config = config;
    }

    protected abstract String providerIdPrefix();

    protected abstract SendResult.PermanentFailure permanentFailure();

    protected abstract SendResult.TransientFailure transientFailure();

    @Override
    public SendResult send(OutboundMessage msg) {
        simulateLatency();

        String existing = sentIds.get(msg.idempotencyKey());
        if (existing != null) {
            return new SendResult.Success(existing);
        }

        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < config.getPermanentFailureRate()) {
            return permanentFailure();
        }
        if (roll < config.getPermanentFailureRate() + config.getTransientFailureRate()) {
            return transientFailure();
        }

        String providerMessageId = providerIdPrefix() + UUID.randomUUID();
        String previous = sentIds.putIfAbsent(msg.idempotencyKey(), providerMessageId);
        return new SendResult.Success(previous != null ? previous : providerMessageId);
    }

    private void simulateLatency() {
        if (config.getLatencyMs() > 0) {
            try {
                Thread.sleep(config.getLatencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

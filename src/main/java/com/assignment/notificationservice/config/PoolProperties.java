package com.assignment.notificationservice.config;

import com.assignment.notificationservice.common.Channel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-channel worker pool sizing. Bound from {@code notify.pools}.
 *
 * <p>Queues are deliberately bounded: when a pool is saturated the dispatcher gets a
 * {@code RejectedExecutionException} and releases the row back to PENDING, rather than
 * buffering unbounded work in memory.
 */
@ConfigurationProperties(prefix = "notify.pools")
@Getter
@Setter
public class PoolProperties {

    private Pool email = new Pool(8, 8, 200);
    private Pool sms = new Pool(4, 4, 100);
    private Pool push = new Pool(8, 8, 200);
    private Pool inApp = new Pool(2, 2, 100);

    public Pool forChannel(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> sms;
            case PUSH -> push;
            case IN_APP -> inApp;
        };
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pool {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
    }
}

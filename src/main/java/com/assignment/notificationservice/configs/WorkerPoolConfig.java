package com.assignment.notificationservice.configs;

import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.ChannelWorkerPools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the per-channel worker pools from {@code notify.pools}.
 *
 * <p>Bounded queue + {@link ThreadPoolExecutor.AbortPolicy}: a full pool rejects instead of
 * buffering without limit (OOM risk) or running the task on the caller (CallerRunsPolicy would
 * stall the dispatcher thread and every other tenant with it).
 */
@Configuration
public class WorkerPoolConfig {

    @Bean(destroyMethod = "shutdown")
    public ChannelWorkerPools channelWorkerPools(PoolProperties poolProperties) {
        Map<Channel, ThreadPoolExecutor> pools = new EnumMap<>(Channel.class);
        for (Channel channel : Channel.values()) {
            pools.put(channel, buildPool(channel, poolProperties.forChannel(channel)));
        }
        return new ChannelWorkerPools(pools);
    }

    private static ThreadPoolExecutor buildPool(Channel channel, PoolProperties.Pool config) {
        String prefix = channel.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-worker-";
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                config.getCoreSize(),
                config.getMaxSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, prefix + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}

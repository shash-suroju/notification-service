package com.assignment.notificationservice.services;

import com.assignment.notificationservice.models.enums.Channel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One bounded {@link ThreadPoolExecutor} per channel, so a slow SMS gateway cannot occupy the
 * threads email needs. Queues are bounded: the dispatcher claims no more than a pool can take
 * ({@link #freeSlots}), and a rejection that still slips through releases the row to PENDING.
 * Built by {@code WorkerPoolConfig}.
 */
public class ChannelWorkerPools {

    private final Map<Channel, ThreadPoolExecutor> pools;

    public ChannelWorkerPools(Map<Channel, ThreadPoolExecutor> pools) {
        this.pools = Collections.unmodifiableMap(new EnumMap<>(pools));
    }

    public ThreadPoolExecutor get(Channel channel) {
        return pools.get(channel);
    }

    /**
     * Tasks the pool can accept right now without rejecting: threads it may still start plus
     * free queue space.
     *
     * <p>Idle threads are deliberately <em>not</em> counted. Once the pool has its core threads,
     * {@code execute()} always offers to the queue first and idle threads drain it afterwards —
     * so a burst of submissions can fill the queue before any idle thread takes a task.
     * Counting idle threads over-reports capacity by up to the thread count and turns into
     * rejections. (Nor {@code max + remainingCapacity - active - queued}: remainingCapacity
     * already excludes queued tasks, so that counts them twice.)
     */
    public int freeSlots(Channel channel) {
        ThreadPoolExecutor pool = pools.get(channel);
        if (pool == null || pool.isShutdown()) {
            return 0;
        }
        int startableThreads = Math.max(0, pool.getMaximumPoolSize() - pool.getPoolSize());
        return startableThreads + pool.getQueue().remainingCapacity();
    }

    /** True when no pool is running or holding any task. */
    public boolean isIdle() {
        return pools.values().stream().allMatch(p -> p.getActiveCount() == 0 && p.getQueue().isEmpty());
    }

    public void shutdown() {
        pools.values().forEach(ThreadPoolExecutor::shutdown);
        pools.values().forEach(p -> {
            try {
                if (!p.awaitTermination(10, TimeUnit.SECONDS)) {
                    p.shutdownNow();
                }
            } catch (InterruptedException e) {
                p.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }
}

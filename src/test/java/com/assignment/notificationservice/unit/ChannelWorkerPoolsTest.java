package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.ChannelWorkerPools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ChannelWorkerPoolsTest {

    private final CountDownLatch gate = new CountDownLatch(1);
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(5), new ThreadPoolExecutor.AbortPolicy());
    private final ChannelWorkerPools pools = new ChannelWorkerPools(Map.of(Channel.EMAIL, pool));

    @AfterEach
    void tearDown() {
        gate.countDown();
        pool.shutdownNow();
    }

    @Test
    void freshPool_countsThreadsItCanStartPlusQueue() {
        assertThat(pools.freeSlots(Channel.EMAIL)).isEqualTo(2 + 5);
    }

    /**
     * Regression: with the core threads already started but idle, execute() offers to the
     * queue first. Counting idle threads as capacity (2 + 5 = 7) overflows the 5-slot queue
     * when 7 tasks are submitted back to back. Submitting exactly freeSlots() must never reject.
     */
    @Test
    void idleStartedThreads_areNotCountedAsCapacity() {
        pool.prestartAllCoreThreads();

        int free = pools.freeSlots(Channel.EMAIL);
        assertThat(free).isEqualTo(5);

        assertThatCode(() -> {
            for (int i = 0; i < free; i++) {
                pool.execute(this::block);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void saturatedPool_hasNoFreeSlots() throws Exception {
        pool.prestartAllCoreThreads();
        pool.execute(this::block);
        pool.execute(this::block);
        while (pool.getActiveCount() < 2 || !pool.getQueue().isEmpty()) {
            Thread.onSpinWait();
        }
        for (int i = 0; i < 5; i++) {
            pool.execute(this::block);
        }

        assertThat(pools.freeSlots(Channel.EMAIL)).isZero();
        assertThat(pools.isIdle()).isFalse();
    }

    @Test
    void unknownOrShutdownPool_hasNoFreeSlots() {
        assertThat(pools.freeSlots(Channel.SMS)).isZero();
        pool.shutdown();
        assertThat(pools.freeSlots(Channel.EMAIL)).isZero();
    }

    private void block() {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

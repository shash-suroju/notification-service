package com.assignment.notificationservice.support;

import com.assignment.notificationservice.dtos.OutboundMessage;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.ChannelSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Deterministic test double for a provider. Results are scripted per idempotency key
 * (= notification ID) and consumed in order; anything unscripted succeeds.
 */
public class ProgrammableSender implements ChannelSender {

    private final Channel channel;
    private final ConcurrentHashMap<String, Queue<Supplier<SendResult>>> programmed = new ConcurrentHashMap<>();
    private final AtomicReference<SendResult> defaultResult = new AtomicReference<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final List<OutboundMessage> messages = Collections.synchronizedList(new ArrayList<>());

    public ProgrammableSender(Channel channel) {
        this.channel = channel;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    /** The next calls for this key return these results in order, then fall back to the default. */
    public void program(String idempotencyKey, SendResult... results) {
        Queue<Supplier<SendResult>> queue = queueFor(idempotencyKey);
        for (SendResult r : results) {
            queue.add(() -> r);
        }
    }

    public void program(UUID notificationId, SendResult... results) {
        program(notificationId.toString(), results);
    }

    /** The next call for this key blocks until {@code release} opens, then returns {@code result}. */
    public void programBlocking(UUID notificationId, CountDownLatch release, SendResult result) {
        queueFor(notificationId.toString()).add(() -> {
            try {
                if (!release.await(60, TimeUnit.SECONDS)) {
                    return new SendResult.TransientFailure("TEST_TIMEOUT", "blocking sender was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result;
        });
    }

    /** The next call for this key throws — simulates a buggy provider client. */
    public void programThrowing(UUID notificationId, RuntimeException exception) {
        queueFor(notificationId.toString()).add(() -> {
            throw exception;
        });
    }

    /** Result for every call that has nothing scripted (null restores "always succeed"). */
    public void programDefault(SendResult result) {
        defaultResult.set(result);
    }

    @Override
    public SendResult send(OutboundMessage msg) {
        callCount.incrementAndGet();
        messages.add(msg);

        Queue<Supplier<SendResult>> queue = programmed.get(msg.idempotencyKey());
        if (queue != null) {
            Supplier<SendResult> next = queue.poll();
            if (next != null) {
                return next.get();
            }
        }
        SendResult fallback = defaultResult.get();
        if (fallback != null) {
            return fallback;
        }
        return new SendResult.Success("mock-" + channel.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID());
    }

    public int getCallCount() {
        return callCount.get();
    }

    /** Idempotency keys in call order. */
    public List<String> getCallLog() {
        synchronized (messages) {
            return messages.stream().map(OutboundMessage::idempotencyKey).toList();
        }
    }

    public List<OutboundMessage> getMessages() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    public long callsFor(UUID notificationId) {
        return getCallLog().stream().filter(k -> k.equals(notificationId.toString())).count();
    }

    public void reset() {
        programmed.clear();
        defaultResult.set(null);
        callCount.set(0);
        messages.clear();
    }

    private Queue<Supplier<SendResult>> queueFor(String key) {
        return programmed.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
    }
}

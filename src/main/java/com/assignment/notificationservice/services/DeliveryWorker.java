package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.DispatchConstants;
import com.assignment.notificationservice.dtos.ClaimedNotification;
import com.assignment.notificationservice.dtos.OutboundMessage;
import com.assignment.notificationservice.dtos.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * One delivery: build the message, call the provider, record the outcome.
 *
 * <p>No database transaction is open during the provider call — only the claim before it and
 * the fenced write after it touch the database. A slow provider therefore holds a lease, not
 * a row lock or a pooled connection.
 */
public class DeliveryWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final ClaimedNotification notification;
    private final ChannelSender sender;
    private final OutcomeRecorder outcomeRecorder;
    private final Clock clock;

    public DeliveryWorker(ClaimedNotification notification, ChannelSender sender,
                          OutcomeRecorder outcomeRecorder, Clock clock) {
        this.notification = notification;
        this.sender = sender;
        this.outcomeRecorder = outcomeRecorder;
        this.clock = clock;
    }

    @Override
    public void run() {
        OutboundMessage msg = new OutboundMessage(
                notification.id(),
                notification.id().toString(),      // provider-side idempotency key
                notification.recipient(),
                notification.subject(),
                notification.body(),
                notification.channel(),
                notification.tenantSlug());

        Instant startedAt = clock.instant();
        SendResult result;
        try {
            result = sender.send(msg);
        } catch (RuntimeException e) {
            // A sender bug must not kill the worker and strand the row until the reaper runs.
            log.error("Sender {} threw for notification {}", sender.getClass().getSimpleName(), notification.id(), e);
            result = new SendResult.TransientFailure(DispatchConstants.ERROR_SENDER_EXCEPTION,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            outcomeRecorder.record(notification, result, startedAt);
        } catch (RuntimeException e) {
            // Row stays PROCESSING; the lease reaper will recover it.
            log.error("Failed to record outcome for notification {}", notification.id(), e);
        }
    }

    public ClaimedNotification getNotification() {
        return notification;
    }
}

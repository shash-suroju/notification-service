package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseDispatcherTest;
import com.assignment.notificationservice.dtos.OutboundMessage;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.DeliveryAttempt;
import com.assignment.notificationservice.models.Notification;
import com.assignment.notificationservice.models.NotificationEvent;
import com.assignment.notificationservice.models.enums.AttemptOutcome;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.repositories.DeliveryAttemptRepository;
import com.assignment.notificationservice.repositories.NotificationEventRepository;
import com.assignment.notificationservice.repositories.NotificationRepository;
import com.assignment.notificationservice.services.InAppSender;
import com.assignment.notificationservice.support.TestSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DispatcherHappyPathTest extends BaseDispatcherTest {

    @Autowired
    private NotificationRepository notificationRepo;

    @Autowired
    private DeliveryAttemptRepository attemptRepo;

    @Autowired
    private NotificationEventRepository eventRepo;

    @Autowired
    private InAppSender inAppSender;

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("disp");
    }

    @Test
    void submit_then_tick_sends_notification() {
        UUID id = idOf(send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com")));
        Instant now = clock.instant();

        assertThat(tickAndAwait()).isEqualTo(1);

        Notification n = notificationRepo.findById(id).orElseThrow();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getAttemptCount()).isEqualTo(1);
        assertThat(n.getSentAt()).isEqualTo(now);
        assertThat(n.getLockedBy()).isNull();
        assertThat(n.getLockedUntil()).isNull();

        List<DeliveryAttempt> attempts = attemptRepo.findByNotificationIdOrderByAttemptNoAsc(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getAttemptNo()).isEqualTo(1);
        assertThat(attempts.get(0).getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(attempts.get(0).getProviderMessageId()).startsWith("mock-email-");

        List<NotificationEvent> events = eventRepo.findByNotificationIdOrderByOccurredAtAscSeqAsc(id);
        assertThat(events)
                .extracting(NotificationEvent::getFromStatus, NotificationEvent::getToStatus,
                        NotificationEvent::getReason, NotificationEvent::getActor)
                .containsExactly(
                        tuple(null, NotificationStatus.PENDING, "immediate_submit", "API"),
                        tuple(NotificationStatus.PENDING, NotificationStatus.PROCESSING, "claimed", "DISPATCHER"),
                        tuple(NotificationStatus.PROCESSING, NotificationStatus.SENT, "provider_success", "DISPATCHER"));

        // The provider received the snapshot rendered at accept time, keyed by notification ID.
        assertThat(emailSender.getCallCount()).isEqualTo(1);
        OutboundMessage msg = emailSender.getMessages().get(0);
        assertThat(msg.idempotencyKey()).isEqualTo(id.toString());
        assertThat(msg.recipient()).isEqualTo("alice@example.com");
        assertThat(msg.subject()).isEqualTo("Welcome to Acme");
        assertThat(msg.body()).isEqualTo("Hello Alice, welcome to Acme!");
        assertThat(msg.tenantSlug()).isEqualTo(sender.tenant().slug());

        // Nothing is left to do: another tick sends nothing.
        assertThat(tickAndAwait()).isZero();
        assertThat(emailSender.getCallCount()).isEqualTo(1);
    }

    @Test
    void multiple_notifications_all_sent_within_the_per_tick_quantum() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(idOf(send(sender, newIdempotencyKey(), welcomeEmail("user" + i + "@example.com"))));
        }

        // base-quantum is 5 in the test profile: one tick moves at most 5 per tenant per channel.
        assertThat(tickAndAwait()).isEqualTo(5);
        assertThat(tickAndAwait()).isEqualTo(5);

        assertThat(ids).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("SENT"));
        assertThat(emailSender.getCallCount()).isEqualTo(10);
        assertThat(new HashSet<>(emailSender.getCallLog())).hasSize(10);   // each sent exactly once
    }

    @Test
    void scheduled_notification_not_sent_before_time() {
        Map<String, Object> request = welcomeEmail("later@example.com");
        request.put("scheduledAt", clock.instant().plus(Duration.ofMinutes(10)).toString());
        UUID id = idOf(send(sender, newIdempotencyKey(), request));

        clock.advance(Duration.ofMinutes(5));
        assertThat(tickAndAwait()).isZero();
        assertThat(statusOf(id)).isEqualTo("SCHEDULED");
        assertThat(emailSender.getCallCount()).isZero();

        clock.advance(Duration.ofMinutes(6));
        assertThat(tickAndAwait()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");

        // SCHEDULED → PROCESSING is not legal: the audit shows the row passing through PENDING.
        assertThat(eventRepo.findByNotificationIdOrderByOccurredAtAscSeqAsc(id))
                .extracting(NotificationEvent::getFromStatus, NotificationEvent::getToStatus, NotificationEvent::getReason)
                .containsExactly(
                        tuple(null, NotificationStatus.SCHEDULED, "scheduled_submit"),
                        tuple(NotificationStatus.SCHEDULED, NotificationStatus.PENDING, "schedule_due"),
                        tuple(NotificationStatus.PENDING, NotificationStatus.PROCESSING, "claimed"),
                        tuple(NotificationStatus.PROCESSING, NotificationStatus.SENT, "provider_success"));
    }

    @Test
    void each_channel_is_routed_to_its_own_sender() {
        UUID sms = idOf(send(sender, newIdempotencyKey(), Map.of("channel", "SMS", "recipient", "+14155551234",
                "templateCode", OTP_TEMPLATE, "variables", Map.of("code", "123456"))));

        tickAndAwait();

        assertThat(statusOf(sms)).isEqualTo("SENT");
        assertThat(smsSender.getCallLog()).containsExactly(sms.toString());
        assertThat(emailSender.getCallCount()).isZero();
        assertThat(pushSender.getCallCount()).isZero();
    }

    @Test
    void in_app_notification_is_written_to_the_inbox_exactly_once() {
        enableChannel(sender.tenant(), "IN_APP");
        createTemplate(sender.tenant(), Map.of("code", "inbox", "channel", "IN_APP",
                "subject", "Hi {{name}}", "body", "Your order has shipped, {{name}}"));
        UUID id = idOf(send(sender, newIdempotencyKey(), Map.of("channel", "IN_APP", "recipient", "user-42",
                "templateCode", "inbox", "variables", Map.of("name", "Alice"))));

        tickAndAwait();

        assertThat(statusOf(id)).isEqualTo("SENT");
        Map<String, Object> inbox = jdbc.queryForMap("SELECT * FROM in_app_message WHERE notification_id = ?", id);
        assertThat(inbox.get("recipient")).isEqualTo("user-42");
        assertThat(inbox.get("title")).isEqualTo("Hi Alice");
        assertThat(inbox.get("body")).isEqualTo("Your order has shipped, Alice");
        assertThat(inbox.get("tenant_id")).isEqualTo(sender.tenant().id());

        // Redelivery (e.g. after a reaped lease) is acknowledged without a second inbox row.
        SendResult again = inAppSender.send(new OutboundMessage(id, id.toString(), "user-42",
                "Hi Alice", "Your order has shipped, Alice", Channel.IN_APP, sender.tenant().slug()));
        assertThat(again).isEqualTo(new SendResult.Success("inapp-duplicate"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM in_app_message WHERE notification_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void suspended_tenants_queued_work_is_not_dispatched() {
        UUID id = idOf(send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com")));
        jdbc.update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", sender.tenant().id());

        assertThat(tickAndAwait()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING");

        jdbc.update("UPDATE tenant SET status = 'ACTIVE' WHERE id = ?", sender.tenant().id());
        assertThat(tickAndAwait()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    void cancelled_notification_is_never_dispatched() {
        UUID id = idOf(send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com")));
        restTemplate.exchange("/api/v1/notifications/" + id + "/cancel", HttpMethod.POST,
                new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), String.class);

        assertThat(tickAndAwait()).isZero();
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(emailSender.getCallCount()).isZero();
    }

    @Test
    void tick_with_nothing_due_is_a_no_op() {
        assertThat(scheduler.tick()).isZero();
    }
}

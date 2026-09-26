package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.common.Channel;
import com.assignment.notificationservice.notification.entity.Notification;
import com.assignment.notificationservice.notification.entity.NotificationStatus;
import com.assignment.notificationservice.notification.statemachine.NotificationStateMachine;
import com.assignment.notificationservice.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DynamicTest;

import static com.assignment.notificationservice.notification.entity.NotificationStatus.CANCELLED;
import static com.assignment.notificationservice.notification.entity.NotificationStatus.FAILED;
import static com.assignment.notificationservice.notification.entity.NotificationStatus.PENDING;
import static com.assignment.notificationservice.notification.entity.NotificationStatus.PROCESSING;
import static com.assignment.notificationservice.notification.entity.NotificationStatus.RETRYING;
import static com.assignment.notificationservice.notification.entity.NotificationStatus.SCHEDULED;
import static com.assignment.notificationservice.notification.entity.NotificationStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive coverage of the transition table: every one of the 49 (from, to) pairs is
 * exercised, so adding a state or widening the whitelist without updating this list fails.
 */
class NotificationStateMachineTest {

    /** The whitelist, restated here independently of the production map. */
    private static final Map<NotificationStatus, Set<NotificationStatus>> LEGAL = Map.of(
            SCHEDULED, Set.of(PENDING, CANCELLED),
            PENDING, Set.of(PROCESSING, CANCELLED),
            PROCESSING, Set.of(SENT, RETRYING, FAILED, PENDING),
            RETRYING, Set.of(PROCESSING),
            FAILED, Set.of(PENDING),
            SENT, Set.of(),
            CANCELLED, Set.of()
    );

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @TestFactory
    @DisplayName("every (from, to) pair behaves per the whitelist")
    List<DynamicTest> everyTransitionPair() {
        List<DynamicTest> tests = new ArrayList<>();
        for (NotificationStatus from : NotificationStatus.values()) {
            for (NotificationStatus to : NotificationStatus.values()) {
                boolean legal = LEGAL.get(from).contains(to);
                tests.add(DynamicTest.dynamicTest(
                        from + " -> " + to + (legal ? " is allowed" : " is rejected"),
                        () -> {
                            if (legal) {
                                assertTransitionSucceeds(from, to);
                            } else {
                                assertTransitionRejected(from, to);
                            }
                        }));
            }
        }
        return tests;
    }

    @Test
    void legalTransitionCountMatchesTheDocumentedTable() {
        long legalPairs = LEGAL.values().stream().mapToLong(Set::size).sum();
        assertThat(legalPairs).isEqualTo(10);
    }

    @Test
    void transitionStampsUpdatedAtFromTheInjectedClock() {
        MutableClock clock = new MutableClock(T0);
        Notification n = notification(PENDING);
        clock.advance(Duration.ofSeconds(42));

        NotificationStateMachine.transition(n, PROCESSING, "claimed", "DISPATCHER", clock);

        assertThat(n.getStatus()).isEqualTo(PROCESSING);
        assertThat(n.getUpdatedAt()).isEqualTo(T0.plusSeconds(42));
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThat(NotificationStateMachine.allowedTargets(SENT)).isEmpty();
        assertThat(NotificationStateMachine.allowedTargets(CANCELLED)).isEmpty();
    }

    @Test
    void rejectedTransitionLeavesTheNotificationUntouched() {
        MutableClock clock = new MutableClock(T0);
        Notification n = notification(SENT);
        Instant originalUpdatedAt = n.getUpdatedAt();

        assertThatThrownBy(() ->
                NotificationStateMachine.transition(n, PROCESSING, "bug", "DISPATCHER", clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENT")
                .hasMessageContaining("PROCESSING");

        assertThat(n.getStatus()).isEqualTo(SENT);
        assertThat(n.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void theDangerousTransitionsAreRejected() {
        // A delivered message must never re-enter the pipeline.
        assertTransitionRejected(SENT, PROCESSING);
        // A cancelled message must never be resurrected.
        assertTransitionRejected(CANCELLED, PENDING);
        assertTransitionRejected(CANCELLED, RETRYING);
        // SENT is only ever reached from PROCESSING — no skipping the provider call.
        assertTransitionRejected(PENDING, SENT);
        assertTransitionRejected(SCHEDULED, SENT);
        // A row in backoff cannot be cancelled or failed without being claimed first.
        assertTransitionRejected(RETRYING, CANCELLED);
        assertTransitionRejected(RETRYING, FAILED);
        // No self-transitions.
        for (NotificationStatus s : NotificationStatus.values()) {
            assertTransitionRejected(s, s);
        }
    }

    private static void assertTransitionSucceeds(NotificationStatus from, NotificationStatus to) {
        MutableClock clock = new MutableClock(T0);
        Notification n = notification(from);

        assertThatCode(() ->
                NotificationStateMachine.transition(n, to, "test", "DISPATCHER", clock))
                .doesNotThrowAnyException();

        assertThat(n.getStatus()).isEqualTo(to);
        assertThat(NotificationStateMachine.isAllowed(from, to)).isTrue();
    }

    private static void assertTransitionRejected(NotificationStatus from, NotificationStatus to) {
        MutableClock clock = new MutableClock(T0);
        Notification n = notification(from);

        assertThatThrownBy(() ->
                NotificationStateMachine.transition(n, to, "test", "DISPATCHER", clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal transition");

        assertThat(n.getStatus()).isEqualTo(from);
        assertThat(NotificationStateMachine.isAllowed(from, to)).isFalse();
    }

    /** A detached in-memory notification — the state machine never touches the database. */
    private static Notification notification(NotificationStatus status) {
        return new Notification(
                null, Channel.EMAIL, "user@example.com",
                "idem-key", "request-hash", "body", status, T0, T0);
    }
}

package com.assignment.notificationservice.services;

import com.assignment.notificationservice.models.Notification;
import com.assignment.notificationservice.models.enums.NotificationStatus;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import static com.assignment.notificationservice.models.enums.NotificationStatus.CANCELLED;
import static com.assignment.notificationservice.models.enums.NotificationStatus.FAILED;
import static com.assignment.notificationservice.models.enums.NotificationStatus.PENDING;
import static com.assignment.notificationservice.models.enums.NotificationStatus.PROCESSING;
import static com.assignment.notificationservice.models.enums.NotificationStatus.RETRYING;
import static com.assignment.notificationservice.models.enums.NotificationStatus.SCHEDULED;
import static com.assignment.notificationservice.models.enums.NotificationStatus.SENT;

/**
 * The single gate every status change passes through.
 *
 * <p>Transitions are whitelisted: anything not listed in {@link #ALLOWED} throws, which is
 * what stops nonsense like SENT → PROCESSING or CANCELLED → RETRYING from ever reaching the
 * database. SENT and CANCELLED are absent as source states because they are terminal.
 *
 * <p>Pure static utility with no Spring dependency, so the whole transition table is unit
 * testable without a context or a database.
 */
public final class NotificationStateMachine {

    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED = Map.of(
            SCHEDULED, Set.of(PENDING, CANCELLED),
            PENDING, Set.of(PROCESSING, CANCELLED),
            PROCESSING, Set.of(SENT, RETRYING, FAILED, PENDING),
            RETRYING, Set.of(PROCESSING),
            FAILED, Set.of(PENDING)
    );

    private NotificationStateMachine() {
        // static utility
    }

    /**
     * Validates and applies a transition.
     *
     * <p>The caller MUST persist a {@code NotificationEvent} carrying {@code reason} and
     * {@code actor} in the same transaction — a status change without an audit row is a bug.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public static void transition(Notification n,
                                  NotificationStatus to,
                                  String reason,
                                  String actor,
                                  Clock clock) {
        NotificationStatus from = n.getStatus();
        if (!isAllowed(from, to)) {
            throw new IllegalStateException("Illegal transition: " + from + " → " + to);
        }
        n.setStatus(to);
        n.setUpdatedAt(clock.instant());
    }

    /** Whether {@code from → to} is in the whitelist. */
    public static boolean isAllowed(NotificationStatus from, NotificationStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** The states reachable from {@code from}; empty for terminal states. */
    public static Set<NotificationStatus> allowedTargets(NotificationStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}

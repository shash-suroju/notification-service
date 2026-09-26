package com.assignment.notificationservice.constants;

/**
 * Allowed values for {@code NotificationEvent.actor}, enforced in the database by the
 * {@code ck_event_actor} check constraint.
 */
public final class EventActors {

    private EventActors() {
    }

    public static final String API = "API";
    public static final String DISPATCHER = "DISPATCHER";
    public static final String REAPER = "REAPER";
    public static final String ADMIN = "ADMIN";
}

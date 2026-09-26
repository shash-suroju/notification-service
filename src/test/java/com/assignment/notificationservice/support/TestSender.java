package com.assignment.notificationservice.support;

/**
 * A tenant ready to send: EMAIL and SMS enabled, templates {@code welcome} (EMAIL) and
 * {@code otp} (SMS) created, and a live API key.
 */
public record TestSender(TestTenant tenant, String apiKey) {
}

package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.AttemptOutcome;

import java.time.Instant;
import java.util.UUID;

/** {@code latencyMs}, {@code outcome} and {@code finishedAt} are null while the attempt is in flight. */
public record DeliveryAttemptDto(
        UUID id,
        int attemptNo,
        AttemptOutcome outcome,
        String errorCode,
        String errorMessage,
        String providerMessageId,
        Long latencyMs,
        Instant startedAt,
        Instant finishedAt
) {
}

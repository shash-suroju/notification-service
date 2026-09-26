package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Keyed by notification ID. Callers must have already resolved the notification through a
 * tenant-scoped query — that lookup is the tenant guard for these rows.
 */
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByNotificationIdOrderByAttemptNoAsc(UUID notificationId);
}

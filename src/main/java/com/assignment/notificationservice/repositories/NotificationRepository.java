package com.assignment.notificationservice.repositories;

import com.assignment.notificationservice.models.Notification;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Every read is tenant-scoped. Reads that feed API responses fetch {@code template} eagerly
 * (entity graph) because open-in-view is off and the response needs the template code.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @EntityGraph(attributePaths = "template")
    Optional<Notification> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = "template")
    Optional<Notification> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * Row-locks the notification ({@code SELECT ... FOR UPDATE}) for a status change.
     * The dispatcher's claim uses {@code SKIP LOCKED}, so it can never grab a row mid-cancel,
     * and a cancel that waits on a claim sees the post-claim status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM Notification n WHERE n.id = :id AND n.tenant.id = :tenantId")
    Optional<Notification> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @EntityGraph(attributePaths = "template")
    Page<Notification> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = "template")
    Page<Notification> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, NotificationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "template")
    Page<Notification> findByTenantIdAndChannelOrderByCreatedAtDesc(
            UUID tenantId, Channel channel, Pageable pageable);

    @EntityGraph(attributePaths = "template")
    Page<Notification> findByTenantIdAndStatusAndChannelOrderByCreatedAtDesc(
            UUID tenantId, NotificationStatus status, Channel channel, Pageable pageable);

    long countByTenantIdAndStatus(UUID tenantId, NotificationStatus status);
}

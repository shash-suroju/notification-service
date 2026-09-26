package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.EventActors;
import com.assignment.notificationservice.constants.NotificationConstants;
import com.assignment.notificationservice.dtos.IngestionResult;
import com.assignment.notificationservice.dtos.NotificationDetailResponse;
import com.assignment.notificationservice.dtos.SendNotificationRequest;
import com.assignment.notificationservice.dtos.SendNotificationResponse;
import com.assignment.notificationservice.exceptions.ChannelDisabledException;
import com.assignment.notificationservice.exceptions.ConflictException;
import com.assignment.notificationservice.exceptions.EntityNotFoundException;
import com.assignment.notificationservice.exceptions.IdempotencyKeyConflictException;
import com.assignment.notificationservice.exceptions.InvalidScheduleTimeException;
import com.assignment.notificationservice.exceptions.TemplateNotFoundException;
import com.assignment.notificationservice.exceptions.TenantSuspendedException;
import com.assignment.notificationservice.models.ChannelConfig;
import com.assignment.notificationservice.models.Notification;
import com.assignment.notificationservice.models.NotificationEvent;
import com.assignment.notificationservice.models.Template;
import com.assignment.notificationservice.models.Tenant;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.models.enums.TenantStatus;
import com.assignment.notificationservice.repositories.ChannelConfigRepository;
import com.assignment.notificationservice.repositories.DeliveryAttemptRepository;
import com.assignment.notificationservice.repositories.NotificationEventRepository;
import com.assignment.notificationservice.repositories.NotificationRepository;
import com.assignment.notificationservice.repositories.TemplateRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import com.assignment.notificationservice.utils.NotificationMapper;
import com.assignment.notificationservice.utils.RecipientValidator;
import com.assignment.notificationservice.utils.RequestHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The only way notifications are created, plus cancel and reads for the send API and console.
 *
 * <h2>Idempotency under concurrency</h2>
 * Two identical submits can both miss the initial lookup and race to INSERT. The
 * {@code UNIQUE(tenant_id, idempotency_key)} constraint lets exactly one win; the loser's
 * INSERT blocks until the winner commits, then fails. Two details make recovery work:
 * <ul>
 *   <li>The INSERT is flushed inside the transaction ({@code saveAndFlush}), so the violation
 *       surfaces here rather than at commit.</li>
 *   <li>After a failed statement PostgreSQL aborts the whole transaction, so the winner cannot
 *       be read in the same one. {@link #submit} is therefore <em>not</em> transactional: the
 *       write runs in its own transaction, and the winner is read after it has rolled back.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NotificationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(NotificationIngestionService.class);
    private static final TypeReference<Map<String, String>> VARIABLES_TYPE = new TypeReference<>() {
    };

    private final NotificationRepository notificationRepository;
    private final NotificationEventRepository eventRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final TemplateRepository templateRepository;
    private final ChannelConfigRepository channelConfigRepository;
    private final TenantRepository tenantRepository;
    private final TemplateRenderer templateRenderer;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /**
     * Validates, renders and persists a notification with its creation audit event.
     *
     * @return {@code created=true} for a new row; {@code created=false} when this key was
     *         already used with an identical payload (a client retry)
     * @throws IdempotencyKeyConflictException if the key was used with a different payload
     */
    public IngestionResult submit(UUID tenantId, String idempotencyKey, SendNotificationRequest request) {
        String requestHash = RequestHasher.hash(
                request.channel().name(), request.recipient(), request.templateCode(), request.variables());

        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> submitInTransaction(tenantId, idempotencyKey, requestHash, request));
        } catch (DataIntegrityViolationException e) {
            if (!isIdempotencyKeyViolation(e)) {
                throw e;
            }
            // Lost the insert race. Our transaction is rolled back; the winner has committed.
            log.debug("Concurrent submit for idempotency key {} lost the race; returning winner", idempotencyKey);
            Notification winner = notificationRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                    .orElseThrow(() -> e);
            return replayOrReject(winner, idempotencyKey, requestHash);
        }
    }

    private IngestionResult submitInTransaction(UUID tenantId, String idempotencyKey,
                                                String requestHash, SendNotificationRequest request) {
        // 1. Idempotency: a sequential retry is answered before any validation runs.
        var existing = notificationRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), idempotencyKey, requestHash);
        }

        // 2. Tenant active + channel enabled
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new TenantSuspendedException(tenantId);
        }
        boolean channelEnabled = channelConfigRepository.findByTenantIdAndChannel(tenantId, request.channel())
                .map(ChannelConfig::isEnabled)
                .orElse(false);
        if (!channelEnabled) {
            throw new ChannelDisabledException(request.channel(), tenantId);
        }

        // 3. Template: latest active version for (code, channel) — a code that exists only
        //    for another channel resolves to nothing here.
        Template template = templateRepository
                .findLatestActive(tenantId, request.templateCode(), request.channel())
                .orElseThrow(() -> new TemplateNotFoundException(request.templateCode(), request.channel()));

        // 4. Recipient format
        RecipientValidator.validate(request.channel(), request.recipient());

        // 5. Schedule bounds
        Instant now = clock.instant();
        Instant scheduledAt = request.scheduledAt();
        if (scheduledAt != null) {
            if (scheduledAt.isBefore(now)) {
                throw new InvalidScheduleTimeException("scheduledAt must be in the future");
            }
            if (scheduledAt.isAfter(now.plus(NotificationConstants.MAX_SCHEDULE_AHEAD))) {
                throw new InvalidScheduleTimeException(
                        "scheduledAt must not be more than "
                                + NotificationConstants.MAX_SCHEDULE_AHEAD.toDays() + " days in the future");
            }
        }

        // 6. Render now and freeze the result: later template edits never change this message.
        Map<String, String> variables = request.variables() != null ? request.variables() : Map.of();
        String renderedSubject = templateRenderer.render(template.getSubject(), variables, request.channel());
        String renderedBody = templateRenderer.render(template.getBody(), variables, request.channel());
        if (request.channel() == Channel.SMS) {
            templateRenderer.validateSmsLength(renderedBody);
        }

        // 7. Persist notification + creation event atomically.
        boolean scheduled = scheduledAt != null;
        NotificationStatus initialStatus = scheduled ? NotificationStatus.SCHEDULED : NotificationStatus.PENDING;

        Notification notification = new Notification(
                tenant, request.channel(), request.recipient(),
                idempotencyKey, requestHash, renderedBody, initialStatus,
                scheduled ? scheduledAt : now, now);
        notification.setTemplate(template);
        notification.setTemplateVersion(template.getVersion());
        notification.setSubject(renderedSubject);
        notification.setVariables(serializeVariables(variables));
        notification.setScheduledAt(scheduledAt);
        notification.setMaxAttempts(tenant.getMaxAttempts());

        // Flush now so a concurrent duplicate fails here, not silently at commit.
        notification = notificationRepository.saveAndFlush(notification);

        eventRepository.save(new NotificationEvent(
                notification, tenantId, null, initialStatus,
                scheduled ? NotificationConstants.REASON_SCHEDULED_SUBMIT : NotificationConstants.REASON_IMMEDIATE_SUBMIT,
                EventActors.API, now));

        return IngestionResult.created(notification);
    }

    /**
     * Cancels a SCHEDULED or PENDING notification. The row is locked first, so a concurrent
     * dispatcher claim cannot slip in between the status check and the write.
     *
     * @param actor {@link EventActors#API} from the send API, {@link EventActors#ADMIN} from the console
     * @throws ConflictException if the notification is in any other status
     */
    @Transactional
    public SendNotificationResponse cancel(UUID tenantId, UUID notificationId, String actor) {
        Notification notification = notificationRepository.findByIdAndTenantIdForUpdate(notificationId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Notification", notificationId));

        NotificationStatus from = notification.getStatus();
        if (from != NotificationStatus.SCHEDULED && from != NotificationStatus.PENDING) {
            throw new ConflictException("Cannot cancel notification in status " + from
                    + ". Only SCHEDULED or PENDING notifications can be cancelled.");
        }

        NotificationStateMachine.transition(notification, NotificationStatus.CANCELLED,
                NotificationConstants.REASON_CANCELLED_BY_USER, actor, clock);
        notificationRepository.save(notification);

        eventRepository.save(new NotificationEvent(
                notification, tenantId, from, NotificationStatus.CANCELLED,
                NotificationConstants.REASON_CANCELLED_BY_USER, actor, notification.getUpdatedAt()));

        // Locked read has no entity graph (FOR UPDATE cannot span the outer join), so load it here.
        Hibernate.initialize(notification.getTemplate());
        return NotificationMapper.toSendResponse(notification);
    }

    /** Notification with its attempts and audit timeline. 404 if it belongs to another tenant. */
    @Transactional(readOnly = true)
    public NotificationDetailResponse getDetail(UUID tenantId, UUID notificationId) {
        Notification n = getByIdAndTenant(notificationId, tenantId);

        return new NotificationDetailResponse(
                n.getId(), n.getStatus(), n.getChannel(), n.getRecipient(),
                n.getTemplate() != null ? n.getTemplate().getCode() : null,
                n.getTemplateVersion(), n.getSubject(), n.getBody(),
                deserializeVariables(n.getVariables()),
                n.getScheduledAt(), n.getAttemptCount(), n.getMaxAttempts(),
                n.getLastErrorCode(), n.getFailureReason(), n.getSentAt(),
                n.getCreatedAt(), n.getUpdatedAt(),
                attemptRepository.findByNotificationIdOrderByAttemptNoAsc(n.getId()).stream()
                        .map(NotificationMapper::toDto).toList(),
                eventRepository.findByNotificationIdOrderByOccurredAtAscSeqAsc(n.getId()).stream()
                        .map(NotificationMapper::toDto).toList());
    }

    @Transactional(readOnly = true)
    public Notification getByIdAndTenant(UUID notificationId, UUID tenantId) {
        return notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Notification", notificationId));
    }

    /** Newest first, optionally filtered by status and/or channel. */
    @Transactional(readOnly = true)
    public Page<SendNotificationResponse> list(UUID tenantId, NotificationStatus status,
                                               Channel channel, Pageable pageable) {
        Page<Notification> page;
        if (status != null && channel != null) {
            page = notificationRepository.findByTenantIdAndStatusAndChannelOrderByCreatedAtDesc(
                    tenantId, status, channel, pageable);
        } else if (status != null) {
            page = notificationRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
        } else if (channel != null) {
            page = notificationRepository.findByTenantIdAndChannelOrderByCreatedAtDesc(tenantId, channel, pageable);
        } else {
            page = notificationRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        }
        return page.map(NotificationMapper::toSendResponse);
    }

    // ---- helpers ----

    private static IngestionResult replayOrReject(Notification existing, String idempotencyKey, String requestHash) {
        if (existing.getRequestHash().equals(requestHash)) {
            return IngestionResult.duplicate(existing);
        }
        throw new IdempotencyKeyConflictException(idempotencyKey);
    }

    /** True only for our idempotency constraint — any other integrity failure must still surface. */
    private static boolean isIdempotencyKeyViolation(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && NotificationConstants.IDEMPOTENCY_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(NotificationConstants.IDEMPOTENCY_CONSTRAINT);
    }

    private String serializeVariables(Map<String, String> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Variables are not serialisable", e);
        }
    }

    private Map<String, String> deserializeVariables(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, VARIABLES_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored notification variables are not a JSON object", e);
        }
    }
}

# H-INGESTION — Notification Ingestion (Critical Path)

> Claude Code: read this file and CLAUDE.md (for rules).
> Prerequisites: H0-2 (skeleton) and H-TEMPLATES (templates, channels, API keys, RBAC) are complete.

---

## IMPORTANT: Repo package structure

The project uses a FLAT package layout, NOT feature-based packages.
All new classes go into the correct package by kind:

```
com.shashank.notificationservice/
├── models/          ← JPA entities (Notification, DeliveryAttempt, NotificationEvent, InAppMessage already here)
├── configs/         ← Spring @Configuration, @ConfigurationProperties
├── controllers/     ← @RestController classes
├── dtos/            ← Request/response records
├── exceptions/      ← Custom exception classes
├── constants/       ← Enums (NotificationStatus, AttemptOutcome, Channel, etc. already here)
├── repositories/    ← Spring Data JPA repositories
├── security/        ← Auth filters, TenantPrincipal, CurrentTenant (already here)
├── services/        ← @Service classes (business logic)
└── utils/           ← Pure utility classes (TemplateRenderer, RequestHasher, etc.)
```

**DO NOT create new sub-packages.** Place every new file in one of the above.

---

## What this block delivers

After this block, the full notification submit → validate → persist → cancel flow works end-to-end:

1. **POST /api/v1/notifications** — submit one notification (API key auth)
2. **Idempotency** — UNIQUE constraint + request hash, concurrent-safe
3. **Validation chain** — channel enabled, template exists + matches channel, recipient format, scheduledAt bounds, variables complete
4. **Template rendering at accept time** — subject + body snapshot stored on notification
5. **Scheduling** — immediate (PENDING) or future (SCHEDULED) based on scheduledAt
6. **Cancel** — POST /notifications/{id}/cancel (only SCHEDULED or PENDING)
7. **GET /notifications/{id}** — status check for the sender
8. **Audit trail** — NotificationEvent inserted in same tx as every status change
9. **Integration tests** — idempotency (20 concurrent threads), validation, cancel, scheduling

Commit message:
```
feat: notification ingestion with idempotency

- POST /notifications: validate, render, persist atomically
- Idempotent submit: UNIQUE(tenant_id, idempotency_key) + request hash
- 20-thread concurrent idempotency integration test
- Validation: channel enabled, template match, recipient format, scheduledAt
- Template rendered at accept time (snapshot on notification row)
- Cancel: SCHEDULED/PENDING → CANCELLED, else 409
- Audit trail: NotificationEvent on every state transition
- RecipientValidator: email regex, E.164 phone, device token, user ID
```

---

## 1. DTOs

### SendNotificationRequest

**Location:** `dtos/SendNotificationRequest.java`

```java
public record SendNotificationRequest(
    @NotNull Channel channel,
    @NotBlank @Size(max = 500) String recipient,
    @NotBlank @Size(max = 100) String templateCode,
    Map<String, String> variables,          // nullable = no variables
    Instant scheduledAt                      // nullable = immediate
) {}
```

### SendNotificationResponse

**Location:** `dtos/SendNotificationResponse.java`

```java
public record SendNotificationResponse(
    UUID id,
    NotificationStatus status,
    Channel channel,
    String recipient,
    String templateCode,
    int templateVersion,
    Instant scheduledAt,
    Instant createdAt
) {}
```

### NotificationDetailResponse

**Location:** `dtos/NotificationDetailResponse.java`

```java
public record NotificationDetailResponse(
    UUID id,
    NotificationStatus status,
    Channel channel,
    String recipient,
    String templateCode,
    int templateVersion,
    String subject,
    String body,
    Map<String, String> variables,
    Instant scheduledAt,
    int attemptCount,
    int maxAttempts,
    String lastErrorCode,
    String failureReason,
    Instant sentAt,
    Instant createdAt,
    Instant updatedAt,
    List<DeliveryAttemptDto> attempts,
    List<NotificationEventDto> timeline
) {}

public record DeliveryAttemptDto(
    UUID id,
    int attemptNo,
    AttemptOutcome outcome,
    String errorCode,
    String errorMessage,
    String providerMessageId,
    long latencyMs,
    Instant startedAt,
    Instant finishedAt
) {}

public record NotificationEventDto(
    NotificationStatus fromStatus,
    NotificationStatus toStatus,
    String reason,
    String actor,
    Instant occurredAt
) {}
```

---

## 2. RecipientValidator

**Location:** `utils/RecipientValidator.java`

Pure utility, no Spring context.

```java
public final class RecipientValidator {

    private RecipientValidator() {}

    // RFC 5322 simplified — good enough for validation, not for parsing
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    // E.164: + followed by 1-15 digits
    private static final Pattern E164_PATTERN =
        Pattern.compile("^\\+[1-9]\\d{1,14}$");

    // Device token: 64+ hex chars (APNs) or alphanumeric (FCM)
    private static final Pattern DEVICE_TOKEN_PATTERN =
        Pattern.compile("^[a-zA-Z0-9_:.-]{32,256}$");

    // In-app user ID: any non-blank string up to 255 chars
    private static final int MAX_USER_ID_LENGTH = 255;

    /**
     * Validate the recipient format for the given channel.
     * @throws InvalidRecipientException if the format is wrong
     */
    public static void validate(Channel channel, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new InvalidRecipientException(channel, recipient, "Recipient cannot be blank");
        }

        switch (channel) {
            case EMAIL -> {
                if (!EMAIL_PATTERN.matcher(recipient).matches()) {
                    throw new InvalidRecipientException(channel, recipient,
                        "Invalid email format");
                }
            }
            case SMS -> {
                if (!E164_PATTERN.matcher(recipient).matches()) {
                    throw new InvalidRecipientException(channel, recipient,
                        "Invalid phone number. Expected E.164 format: +<country><number>");
                }
            }
            case PUSH -> {
                if (!DEVICE_TOKEN_PATTERN.matcher(recipient).matches()) {
                    throw new InvalidRecipientException(channel, recipient,
                        "Invalid device token format");
                }
            }
            case IN_APP -> {
                if (recipient.length() > MAX_USER_ID_LENGTH) {
                    throw new InvalidRecipientException(channel, recipient,
                        "User ID exceeds max length of " + MAX_USER_ID_LENGTH);
                }
            }
        }
    }
}
```

### InvalidRecipientException

**Location:** `exceptions/InvalidRecipientException.java`

```java
public class InvalidRecipientException extends RuntimeException {
    private final Channel channel;
    private final String recipient;

    public InvalidRecipientException(Channel channel, String recipient, String message) {
        super(message);
        this.channel = channel;
        this.recipient = recipient;
    }

    public Channel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
}
```

Add to `GlobalExceptionHandler`:
```java
@ExceptionHandler(InvalidRecipientException.class)
public ProblemDetail handleInvalidRecipient(InvalidRecipientException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    pd.setTitle("Invalid Recipient");
    pd.setProperty("channel", ex.getChannel());
    return pd;
}
```

---

## 3. RequestHasher

**Location:** `utils/RequestHasher.java`

If already created from H-TEMPLATES, keep it. Otherwise create now:

```java
public final class RequestHasher {

    private RequestHasher() {}

    /**
     * Deterministic SHA-256 hash of the notification payload.
     * Canonical form: channel|recipient|templateCode|k1=v1,k2=v2,...
     * Variables sorted by key for stability regardless of JSON key order.
     */
    public static String hash(String channel, String recipient, String templateCode,
                               Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();
        sb.append(channel).append('|');
        sb.append(recipient).append('|');
        sb.append(templateCode).append('|');

        if (variables != null && !variables.isEmpty()) {
            variables.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(','));
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
```

---

## 4. Repositories

### NotificationRepository

**Location:** `repositories/NotificationRepository.java`

```java
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Notification> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Page<Notification> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<Notification> findByTenantIdAndStatusOrderByCreatedAtDesc(
        UUID tenantId, NotificationStatus status, Pageable pageable);

    Page<Notification> findByTenantIdAndChannelOrderByCreatedAtDesc(
        UUID tenantId, Channel channel, Pageable pageable);

    Page<Notification> findByTenantIdAndStatusAndChannelOrderByCreatedAtDesc(
        UUID tenantId, NotificationStatus status, Channel channel, Pageable pageable);

    long countByTenantIdAndStatus(UUID tenantId, NotificationStatus status);
}
```

### DeliveryAttemptRepository

**Location:** `repositories/DeliveryAttemptRepository.java`

```java
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByNotificationIdOrderByAttemptNoAsc(UUID notificationId);
}
```

### NotificationEventRepository

**Location:** `repositories/NotificationEventRepository.java`

```java
public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {

    List<NotificationEvent> findByNotificationIdOrderByOccurredAtAsc(UUID notificationId);
}
```

---

## 5. NotificationIngestionService — the core

**Location:** `services/NotificationIngestionService.java`

This is the most important class in the entire ingestion path. Every validation,
every guard, every edge case lives here.

```java
@Service
public class NotificationIngestionService {

    private final NotificationRepository notificationRepository;
    private final NotificationEventRepository eventRepository;
    private final TemplateRepository templateRepository;
    private final ChannelConfigRepository channelConfigRepository;
    private final TenantRepository tenantRepository;
    private final TemplateRenderer templateRenderer;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(30);

    // constructor injection — all fields final

    /**
     * Submit a notification. This is the ONLY entry point for creating notifications.
     *
     * Flow:
     * 1. Idempotency check (existing key?)
     * 2. Validate channel enabled for tenant
     * 3. Resolve template (latest active version for code + channel)
     * 4. Validate recipient format
     * 5. Validate scheduledAt bounds
     * 6. Render template (snapshot subject + body)
     * 7. Persist notification + audit event in ONE transaction
     * 8. Handle constraint violation race (concurrent idempotent submit)
     */
    @Transactional
    public IngestionResult submit(UUID tenantId, String idempotencyKey,
                                   SendNotificationRequest request) {

        // --- 1. Idempotency check ---
        String requestHash = RequestHasher.hash(
            request.channel().name(),
            request.recipient(),
            request.templateCode(),
            request.variables()
        );

        Optional<Notification> existing = notificationRepository
            .findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);

        if (existing.isPresent()) {
            Notification e = existing.get();
            if (e.getRequestHash().equals(requestHash)) {
                // Same key, same payload → return existing (200 OK)
                return IngestionResult.duplicate(e);
            } else {
                // Same key, different payload → reject (422)
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
        }

        // --- 2. Validate channel enabled ---
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));

        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new TenantSuspendedException(tenantId);
        }

        boolean channelEnabled = channelConfigRepository
            .findByTenantIdAndChannel(tenantId, request.channel())
            .map(ChannelConfig::isEnabled)
            .orElse(false);

        if (!channelEnabled) {
            throw new ChannelDisabledException(request.channel(), tenantId);
        }

        // --- 3. Resolve template ---
        Template template = templateRepository
            .findLatestActive(tenantId, request.templateCode(), request.channel())
            .orElseThrow(() -> new TemplateNotFoundException(
                request.templateCode(), request.channel()));

        // --- 4. Validate recipient format ---
        RecipientValidator.validate(request.channel(), request.recipient());

        // --- 5. Validate scheduledAt ---
        Instant now = clock.instant();
        if (request.scheduledAt() != null) {
            if (request.scheduledAt().isBefore(now)) {
                throw new InvalidScheduleTimeException("scheduledAt must be in the future");
            }
            if (request.scheduledAt().isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
                throw new InvalidScheduleTimeException(
                    "scheduledAt must not be more than 30 days in the future");
            }
        }

        // --- 6. Render template ---
        Map<String, String> variables = request.variables() != null
            ? request.variables() : Map.of();

        String renderedSubject = templateRenderer.render(
            template.getSubject(), variables, request.channel());
        String renderedBody = templateRenderer.render(
            template.getBody(), variables, request.channel());

        // SMS length validation
        if (request.channel() == Channel.SMS) {
            templateRenderer.validateSmsLength(renderedBody);
        }

        // --- 7. Build and persist notification ---
        boolean isScheduled = request.scheduledAt() != null;
        NotificationStatus initialStatus = isScheduled
            ? NotificationStatus.SCHEDULED
            : NotificationStatus.PENDING;

        Notification notification = new Notification();
        notification.setTenant(tenant);
        notification.setChannel(request.channel());
        notification.setRecipient(request.recipient());
        notification.setIdempotencyKey(idempotencyKey);
        notification.setRequestHash(requestHash);
        notification.setTemplate(template);
        notification.setTemplateVersion(template.getVersion());
        notification.setSubject(renderedSubject);
        notification.setBody(renderedBody);
        notification.setVariables(serializeVariables(variables));
        notification.setStatus(initialStatus);
        notification.setScheduledAt(request.scheduledAt());
        notification.setNextAttemptAt(isScheduled ? request.scheduledAt() : now);
        notification.setAttemptCount(0);
        notification.setMaxAttempts(tenant.getMaxAttempts());
        notification.setCreatedAt(now);
        notification.setUpdatedAt(now);

        try {
            notification = notificationRepository.save(notification);
        } catch (DataIntegrityViolationException e) {
            // --- 8. Race condition: another thread inserted first ---
            // Unique constraint on (tenant_id, idempotency_key) violated
            Notification winner = notificationRepository
                .findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .orElseThrow(() -> e);  // shouldn't happen, but don't swallow

            if (winner.getRequestHash().equals(requestHash)) {
                return IngestionResult.duplicate(winner);
            } else {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
        }

        // --- Audit event (SAME transaction) ---
        NotificationEvent event = new NotificationEvent();
        event.setNotification(notification);
        event.setTenantId(tenantId);
        event.setFromStatus(null);       // initial state
        event.setToStatus(initialStatus);
        event.setReason(isScheduled ? "scheduled_submit" : "immediate_submit");
        event.setActor("API");
        event.setOccurredAt(now);
        eventRepository.save(event);

        return IngestionResult.created(notification);
    }

    /**
     * Cancel a notification. Only allowed when SCHEDULED or PENDING.
     */
    @Transactional
    public Notification cancel(UUID tenantId, UUID notificationId) {
        Notification notification = notificationRepository
            .findByIdAndTenantId(notificationId, tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Notification", notificationId));

        NotificationStatus from = notification.getStatus();

        // Guard: only SCHEDULED and PENDING can be cancelled
        if (from != NotificationStatus.SCHEDULED && from != NotificationStatus.PENDING) {
            throw new ConflictException(
                "Cannot cancel notification in status " + from +
                ". Only SCHEDULED or PENDING notifications can be cancelled.");
        }

        Instant now = clock.instant();

        // Transition via state machine
        NotificationStateMachine.transition(
            notification, NotificationStatus.CANCELLED,
            "cancelled_by_user", "API", clock
        );

        notificationRepository.save(notification);

        // Audit event (SAME transaction)
        NotificationEvent event = new NotificationEvent();
        event.setNotification(notification);
        event.setTenantId(tenantId);
        event.setFromStatus(from);
        event.setToStatus(NotificationStatus.CANCELLED);
        event.setReason("cancelled_by_user");
        event.setActor("API");
        event.setOccurredAt(now);
        eventRepository.save(event);

        return notification;
    }

    /**
     * Get notification detail, scoped to tenant.
     */
    public Notification getByIdAndTenant(UUID notificationId, UUID tenantId) {
        return notificationRepository.findByIdAndTenantId(notificationId, tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Notification", notificationId));
    }

    /**
     * List notifications for a tenant, with optional status and channel filters.
     */
    public Page<Notification> list(UUID tenantId, NotificationStatus status,
                                    Channel channel, Pageable pageable) {
        if (status != null && channel != null) {
            return notificationRepository
                .findByTenantIdAndStatusAndChannelOrderByCreatedAtDesc(tenantId, status, channel, pageable);
        } else if (status != null) {
            return notificationRepository
                .findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
        } else if (channel != null) {
            return notificationRepository
                .findByTenantIdAndChannelOrderByCreatedAtDesc(tenantId, channel, pageable);
        }
        return notificationRepository
            .findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    private String serializeVariables(Map<String, String> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize variables", e);
        }
    }
}
```

### IngestionResult

**Location:** `dtos/IngestionResult.java`

```java
public record IngestionResult(
    Notification notification,
    boolean created          // true = new (202), false = duplicate (200)
) {
    public static IngestionResult created(Notification n) {
        return new IngestionResult(n, true);
    }

    public static IngestionResult duplicate(Notification n) {
        return new IngestionResult(n, false);
    }
}
```

---

## 6. Custom exceptions for ingestion

All in the `exceptions/` package.

**IdempotencyKeyConflictException.java**
```java
public class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException(String key) {
        super("Idempotency key '" + key + "' already used with a different payload");
    }
}
```

**TenantSuspendedException.java**
```java
public class TenantSuspendedException extends RuntimeException {
    public TenantSuspendedException(UUID tenantId) {
        super("Tenant " + tenantId + " is suspended");
    }
}
```

**ChannelDisabledException.java**
```java
public class ChannelDisabledException extends RuntimeException {
    private final Channel channel;

    public ChannelDisabledException(Channel channel, UUID tenantId) {
        super("Channel " + channel + " is not enabled for this tenant");
        this.channel = channel;
    }

    public Channel getChannel() { return channel; }
}
```

**TemplateNotFoundException.java**
```java
public class TemplateNotFoundException extends RuntimeException {
    public TemplateNotFoundException(String code, Channel channel) {
        super("No active template found with code '" + code + "' for channel " + channel);
    }
}
```

**InvalidScheduleTimeException.java**
```java
public class InvalidScheduleTimeException extends RuntimeException {
    public InvalidScheduleTimeException(String message) {
        super(message);
    }
}
```

### GlobalExceptionHandler additions

```java
@ExceptionHandler(IdempotencyKeyConflictException.class)
public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    pd.setTitle("Idempotency Key Conflict");
    return pd;
}

@ExceptionHandler(TenantSuspendedException.class)
public ProblemDetail handleTenantSuspended(TenantSuspendedException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
}

@ExceptionHandler(ChannelDisabledException.class)
public ProblemDetail handleChannelDisabled(ChannelDisabledException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    pd.setTitle("Channel Disabled");
    pd.setProperty("channel", ex.getChannel());
    return pd;
}

@ExceptionHandler(TemplateNotFoundException.class)
public ProblemDetail handleTemplateNotFound(TemplateNotFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
}

@ExceptionHandler(InvalidScheduleTimeException.class)
public ProblemDetail handleInvalidScheduleTime(InvalidScheduleTimeException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
}
```

---

## 7. NotificationController

**Location:** `controllers/NotificationController.java`

**Base path:** `/api/v1/notifications`
**Auth:** `X-API-Key` header (resolved by ApiKeyAuthFilter → sets TenantPrincipal)

```java
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationIngestionService ingestionService;
    private final DeliveryAttemptRepository attemptRepository;
    private final NotificationEventRepository eventRepository;

    // constructor injection

    /**
     * Submit a notification.
     * Requires Idempotency-Key header.
     */
    @PostMapping
    public ResponseEntity<SendNotificationResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SendNotificationRequest request) {

        UUID tenantId = CurrentTenant.resolve();

        // Validate idempotency key is not blank
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }

        IngestionResult result = ingestionService.submit(tenantId, idempotencyKey, request);

        Notification n = result.notification();
        SendNotificationResponse response = new SendNotificationResponse(
            n.getId(),
            n.getStatus(),
            n.getChannel(),
            n.getRecipient(),
            n.getTemplate() != null ? n.getTemplate().getCode() : null,
            n.getTemplateVersion(),
            n.getScheduledAt(),
            n.getCreatedAt()
        );

        // 202 Accepted for new, 200 OK for duplicate
        HttpStatus status = result.created()
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;

        return ResponseEntity.status(status).body(response);
    }

    /**
     * Get notification status + detail.
     */
    @GetMapping("/{id}")
    public NotificationDetailResponse getById(@PathVariable UUID id) {
        UUID tenantId = CurrentTenant.resolve();
        Notification n = ingestionService.getByIdAndTenant(id, tenantId);

        List<DeliveryAttempt> attempts = attemptRepository
            .findByNotificationIdOrderByAttemptNoAsc(n.getId());
        List<NotificationEvent> events = eventRepository
            .findByNotificationIdOrderByOccurredAtAsc(n.getId());

        return toDetailResponse(n, attempts, events);
    }

    /**
     * Cancel a notification (only SCHEDULED or PENDING).
     */
    @PostMapping("/{id}/cancel")
    public SendNotificationResponse cancel(@PathVariable UUID id) {
        UUID tenantId = CurrentTenant.resolve();
        Notification n = ingestionService.cancel(tenantId, id);

        return new SendNotificationResponse(
            n.getId(), n.getStatus(), n.getChannel(), n.getRecipient(),
            n.getTemplate() != null ? n.getTemplate().getCode() : null,
            n.getTemplateVersion(), n.getScheduledAt(), n.getCreatedAt()
        );
    }

    /**
     * List notifications with optional filters.
     */
    @GetMapping
    public Page<SendNotificationResponse> list(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID tenantId = CurrentTenant.resolve();
        size = Math.min(size, 100);

        return ingestionService.list(tenantId, status, channel, PageRequest.of(page, size))
            .map(this::toSendResponse);
    }

    // --- mappers ---

    private SendNotificationResponse toSendResponse(Notification n) {
        return new SendNotificationResponse(
            n.getId(), n.getStatus(), n.getChannel(), n.getRecipient(),
            n.getTemplate() != null ? n.getTemplate().getCode() : null,
            n.getTemplateVersion(), n.getScheduledAt(), n.getCreatedAt()
        );
    }

    private NotificationDetailResponse toDetailResponse(
            Notification n, List<DeliveryAttempt> attempts, List<NotificationEvent> events) {

        List<DeliveryAttemptDto> attemptDtos = attempts.stream()
            .map(a -> new DeliveryAttemptDto(
                a.getId(), a.getAttemptNo(), a.getOutcome(),
                a.getErrorCode(), a.getErrorMessage(), a.getProviderMessageId(),
                a.getLatencyMs(), a.getStartedAt(), a.getFinishedAt()))
            .toList();

        List<NotificationEventDto> eventDtos = events.stream()
            .map(e -> new NotificationEventDto(
                e.getFromStatus(), e.getToStatus(),
                e.getReason(), e.getActor(), e.getOccurredAt()))
            .toList();

        Map<String, String> variables = deserializeVariables(n.getVariables());

        return new NotificationDetailResponse(
            n.getId(), n.getStatus(), n.getChannel(), n.getRecipient(),
            n.getTemplate() != null ? n.getTemplate().getCode() : null,
            n.getTemplateVersion(), n.getSubject(), n.getBody(),
            variables, n.getScheduledAt(), n.getAttemptCount(),
            n.getMaxAttempts(), n.getLastErrorCode(), n.getFailureReason(),
            n.getSentAt(), n.getCreatedAt(), n.getUpdatedAt(),
            attemptDtos, eventDtos
        );
    }

    private Map<String, String> deserializeVariables(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
```

### Missing Idempotency-Key header handling

Spring won't give a nice error for a missing required `@RequestHeader`. Add to the controller
or to `GlobalExceptionHandler`:

```java
@ExceptionHandler(MissingRequestHeaderException.class)
public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Required header '" + ex.getHeaderName() + "' is missing"
    );
    pd.setTitle("Missing Required Header");
    return pd;
}
```

---

## 8. Unit tests

### RecipientValidatorTest

**Location:** `src/test/java/.../unit/RecipientValidatorTest.java`

```java
class RecipientValidatorTest {

    // --- EMAIL ---
    @Test void email_valid()                { validate(EMAIL, "user@example.com"); }
    @Test void email_valid_withPlus()        { validate(EMAIL, "user+tag@example.com"); }
    @Test void email_invalid_noAt()          { assertThrows(EMAIL, "userexample.com"); }
    @Test void email_invalid_noDomain()      { assertThrows(EMAIL, "user@"); }
    @Test void email_invalid_noTld()         { assertThrows(EMAIL, "user@example"); }
    @Test void email_invalid_blank()         { assertThrows(EMAIL, ""); }

    // --- SMS (E.164) ---
    @Test void sms_valid_us()               { validate(SMS, "+14155551234"); }
    @Test void sms_valid_india()            { validate(SMS, "+918184911672"); }
    @Test void sms_invalid_noPlus()         { assertThrows(SMS, "14155551234"); }
    @Test void sms_invalid_tooShort()       { assertThrows(SMS, "+1"); }
    @Test void sms_invalid_letters()        { assertThrows(SMS, "+1415abc1234"); }
    @Test void sms_invalid_startsWithZero() { assertThrows(SMS, "+0123456789"); }

    // --- PUSH ---
    @Test void push_valid_longToken()       { validate(PUSH, "a".repeat(64)); }
    @Test void push_invalid_tooShort()      { assertThrows(PUSH, "abc"); }

    // --- IN_APP ---
    @Test void inApp_valid_userId()         { validate(IN_APP, "user-123"); }
    @Test void inApp_invalid_tooLong()      { assertThrows(IN_APP, "a".repeat(256)); }
    @Test void inApp_invalid_blank()        { assertThrows(IN_APP, "  "); }

    // --- null ---
    @Test void null_recipient_throws()      { assertThrows(EMAIL, null); }

    // helpers
    private void validate(Channel ch, String r) {
        assertDoesNotThrow(() -> RecipientValidator.validate(ch, r));
    }
    private void assertThrows(Channel ch, String r) {
        org.junit.jupiter.api.Assertions.assertThrows(
            InvalidRecipientException.class,
            () -> RecipientValidator.validate(ch, r));
    }
}
```

### RequestHasherTest

If not already created, see H-TEMPLATES spec. Key tests:
- Stable across variable key order
- Different payloads → different hashes
- Null and empty variables handled
- Output is 64-char hex string

---

## 9. Integration tests

### IngestionHappyPathTest

**Location:** `src/test/java/.../integration/IngestionHappyPathTest.java`

```java
class IngestionHappyPathTest extends BaseIntegrationTest {

    // Setup: tenant with EMAIL enabled, template "welcome" for EMAIL, API key

    @Test void submit_immediate_returns202_statusPending() {
        // POST /notifications with valid payload, no scheduledAt
        // → 202, status = PENDING
        // Verify: notification row exists with correct fields
        // Verify: one NotificationEvent row (null → PENDING, actor=API)
    }

    @Test void submit_scheduled_returns202_statusScheduled() {
        // POST with scheduledAt = now + 1 hour
        // → 202, status = SCHEDULED
        // Verify: nextAttemptAt = scheduledAt
    }

    @Test void submit_rendersTemplateAtAcceptTime() {
        // Template: "Hello {{name}}"
        // POST with variables: {name: "Alice"}
        // Verify: notification.body = "Hello Alice" (snapshot)
    }

    @Test void getById_returnsDetailWithTimeline() {
        // Submit → GET /notifications/{id}
        // → 200 with status, attempts (empty), timeline (1 event)
    }
}
```

### IngestionIdempotencyTest — THE critical test

**Location:** `src/test/java/.../integration/IngestionIdempotencyTest.java`

```java
class IngestionIdempotencyTest extends BaseIntegrationTest {

    @Test void concurrent_submit_same_key_creates_exactly_one() {
        // THIS IS THE MOST IMPORTANT TEST IN THE ENTIRE REPO.
        //
        // 20 threads all submit the exact same notification simultaneously:
        // same idempotency key, same payload.
        //
        // Expected: exactly 1 notification row in the DB.
        // All 20 responses return the same notification ID.
        // No exceptions thrown (losers get 200, winner gets 202).

        int threadCount = 20;
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        List<ResponseEntity<SendNotificationResponse>> responses =
            Collections.synchronizedList(new ArrayList<>());
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startGate.await();    // all threads start at the same instant

                    HttpHeaders headers = new HttpHeaders();
                    headers.set("X-API-Key", testApiKey);
                    headers.set("Idempotency-Key", idempotencyKey);
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    SendNotificationRequest request = new SendNotificationRequest(
                        Channel.EMAIL, "test@example.com", "welcome",
                        Map.of("name", "Alice", "companyName", "Acme"), null
                    );

                    HttpEntity<SendNotificationRequest> entity = new HttpEntity<>(request, headers);
                    ResponseEntity<SendNotificationResponse> resp = restTemplate.postForEntity(
                        "/api/v1/notifications", entity, SendNotificationResponse.class);
                    responses.add(resp);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    endGate.countDown();
                }
            }).start();
        }

        startGate.countDown();    // release all threads
        endGate.await(10, TimeUnit.SECONDS);

        // Assertions
        assertThat(errors).isEmpty();
        assertThat(responses).hasSize(threadCount);

        // All responses should return the same notification ID
        Set<UUID> ids = responses.stream()
            .map(r -> r.getBody().id())
            .collect(Collectors.toSet());
        assertThat(ids).hasSize(1);   // exactly one unique ID

        // Exactly one 202, rest are 200
        long created = responses.stream()
            .filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED)
            .count();
        long duplicates = responses.stream()
            .filter(r -> r.getStatusCode() == HttpStatus.OK)
            .count();
        assertThat(created + duplicates).isEqualTo(threadCount);
        assertThat(created).isEqualTo(1);

        // DB has exactly one row
        long dbCount = notificationRepository
            .countByTenantIdAndStatus(testTenantId, NotificationStatus.PENDING);
        // (could be PENDING or the one row regardless of status)
        assertThat(notificationRepository.findByTenantIdAndIdempotencyKey(
            testTenantId, idempotencyKey)).isPresent();
    }

    @Test void same_key_different_payload_returns422() {
        String key = "dup-key-" + UUID.randomUUID();

        // First submit: succeeds
        submit(key, "test@example.com", "welcome", Map.of("name", "Alice"));

        // Second submit: same key, different recipient
        var response = submit(key, "other@example.com", "welcome", Map.of("name", "Bob"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test void same_key_same_payload_returns200_with_original() {
        String key = "retry-key-" + UUID.randomUUID();

        var first = submit(key, "test@example.com", "welcome", Map.of("name", "Alice"));
        var second = submit(key, "test@example.com", "welcome", Map.of("name", "Alice"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().id()).isEqualTo(second.getBody().id());
    }
}
```

### IngestionValidationTest

**Location:** `src/test/java/.../integration/IngestionValidationTest.java`

```java
class IngestionValidationTest extends BaseIntegrationTest {

    @Test void missing_idempotency_key_header_returns400() {
        // POST without Idempotency-Key header → 400
    }

    @Test void disabled_channel_returns400() {
        // Disable SMS for tenant → submit SMS notification → 400
    }

    @Test void unknown_template_code_returns400() {
        // Submit with templateCode "nonexistent" → 400
    }

    @Test void template_channel_mismatch_returns400() {
        // Template is for EMAIL, submit for SMS → 400
        // (findLatestActive query matches both code AND channel, so returns empty)
    }

    @Test void invalid_email_recipient_returns400() {
        // Submit EMAIL to "not-an-email" → 400
    }

    @Test void invalid_e164_phone_returns400() {
        // Submit SMS to "12345" (no +) → 400
    }

    @Test void missing_template_variable_returns400() {
        // Template has {{name}}, submit with no variables → 400
    }

    @Test void scheduledAt_in_past_returns400() {
        // Submit with scheduledAt = now - 1 hour → 400
    }

    @Test void scheduledAt_too_far_ahead_returns400() {
        // Submit with scheduledAt = now + 31 days → 400
    }

    @Test void suspended_tenant_returns403() {
        // Suspend tenant → submit → 403
    }

    @Test void invalid_api_key_returns401() {
        // Submit with bad X-API-Key → 401
    }
}
```

### CancelNotificationTest

**Location:** `src/test/java/.../integration/CancelNotificationTest.java`

```java
class CancelNotificationTest extends BaseIntegrationTest {

    @Test void cancel_scheduled_notification_succeeds() {
        // Submit scheduled → cancel → status = CANCELLED
        // Verify: audit event SCHEDULED → CANCELLED, reason=cancelled_by_user
    }

    @Test void cancel_pending_notification_succeeds() {
        // Submit immediate → cancel → status = CANCELLED
    }

    @Test void cancel_sent_notification_returns409() {
        // (We can't easily get to SENT without the dispatcher,
        //  but we can test with a notification manually set to SENT)
        // → 409 Conflict
    }

    @Test void cancel_nonexistent_notification_returns404() {
        // Cancel random UUID → 404
    }

    @Test void cancel_other_tenants_notification_returns404() {
        // Submit as tenant A → cancel as tenant B → 404
    }

    @Test void cancel_is_idempotent_for_already_cancelled() {
        // Cancel → cancel again → 409 (CANCELLED is not in the allowed from-states)
        // This is correct behavior: cancel is not idempotent at the state machine level
    }
}
```

---

## 10. SecurityConfig update

Make sure the send API path is accessible with API key auth:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/admin/**").hasRole("PLATFORM_ADMIN")
    .requestMatchers("/api/v1/tenant/**").hasRole("TENANT_ADMIN")
    // Send API — authenticated via API key (ApiKeyAuthFilter sets TENANT_ADMIN role)
    .requestMatchers("/api/v1/notifications/**").authenticated()
    .anyRequest().authenticated()
)
```

The `ApiKeyAuthFilter` from H-TEMPLATES already handles `X-API-Key` on `/api/v1/notifications/**`.
Verify its `shouldNotFilter` method allows this path:

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/v1/notifications");
}
```

If the tenant admin needs to also access `/api/v1/notifications/**` via HTTP Basic (for the
list/detail endpoints under the tenant admin panel), you have two options:
1. Keep send API on `/api/v1/notifications` (API key) and add tenant admin read-only endpoints
   under `/api/v1/tenant/notifications` (HTTP Basic) — **recommended, cleaner separation**
2. Allow both auth methods on the same path

Go with option 1: add a separate `TenantNotificationController` at `/api/v1/tenant/notifications`
for the tenant admin to list and view notifications:

```java
@RestController
@RequestMapping("/api/v1/tenant/notifications")
public class TenantNotificationController {

    // GET /                → list (paginated, filtered by status/channel)
    // GET /{id}            → detail with attempts + timeline
    // POST /{id}/cancel    → cancel
    // POST /{id}/retry     → manual retry (implement in H16-19)
}
```

This means `/api/v1/notifications` (API key) handles submit + status check for the sending app,
and `/api/v1/tenant/notifications` (HTTP Basic) handles management for the tenant admin.

---

## 11. Checklist before commit

- [ ] `./gradlew test` passes — all unit + integration tests green
- [ ] POST /notifications with valid payload → 202 with PENDING status
- [ ] POST /notifications with scheduledAt → 202 with SCHEDULED status
- [ ] Template rendered at accept time (body/subject snapshot on notification)
- [ ] 20-thread concurrent idempotency test passes: exactly 1 row, all same ID
- [ ] Same key + different payload → 422
- [ ] Same key + same payload → 200 with original
- [ ] Disabled channel → 400
- [ ] Unknown template → 400
- [ ] Invalid recipient (email, phone, token) → 400
- [ ] Missing template variable → 400
- [ ] scheduledAt in past → 400, > 30 days → 400
- [ ] Suspended tenant → 403
- [ ] Cancel SCHEDULED/PENDING → CANCELLED with audit event
- [ ] Cancel SENT/FAILED → 409
- [ ] NotificationEvent row exists for every status transition
- [ ] No `Instant.now()` anywhere — all use injected Clock
- [ ] All new classes in correct flat package (models/, services/, etc.)

# Notification Service — Implementation Plan

> This document is the engineering blueprint for H0–2.
> It defines every entity, every table, every state transition, and exactly how
> the clock/queue/mock machinery works so there are zero ambiguities during coding.

---

## 1. Project skeleton

Gradle build, layered (MVC) package layout under `com.assignment.notificationservice`.
Code is grouped by **layer**, not by feature: every controller lives in `controllers/`, every
entity in `models/`, and so on.

Legend: unmarked = implemented · `[H8-11]` etc. = planned, delivered in that block.

```
notification-service/
├── build.gradle                          — Spring Boot 3.3.5, Java 21, Lombok, Testcontainers
├── settings.gradle
├── gradlew / gradlew.bat                 — Gradle 8.14.3 wrapper
├── gradle/wrapper/
├── CLAUDE.md                             — AI working agreement (rules)
├── plan.md                               ← this file
├── design-patterns.md                    — every pattern used and why
├── H-TEMPLATES.md                        — templates / channel config / API keys spec
├── README.md                             [H21-23]
├── src/
│   ├── main/
│   │   ├── java/com/assignment/notificationservice/
│   │   │   ├── NotificationServiceApplication.java
│   │   │   │
│   │   │   ├── configs/
│   │   │   │   ├── ClockConfig.java               — injectable Clock bean
│   │   │   │   ├── SecurityConfig.java            — RBAC, HTTP Basic + API key filter chain
│   │   │   │   ├── DispatcherProperties.java      — notify.dispatcher (tick/lease/quantum)
│   │   │   │   ├── PoolProperties.java            — notify.pools (per-channel pool sizes)
│   │   │   │   ├── RetryProperties.java           — notify.retry (backoff, max attempts)
│   │   │   │   ├── MockProviderProperties.java    — notify.mock-providers (latency, failure rates)
│   │   │   │   └── ExecutorConfig.java            [H11-16] per-channel ThreadPoolExecutor beans
│   │   │   │
│   │   │   ├── constants/
│   │   │   │   ├── ApiPaths.java                  — every URL; shared by controllers + SecurityConfig
│   │   │   │   ├── SecurityConstants.java         — X-API-Key header, key format, role names
│   │   │   │   ├── TemplateConstants.java         — SMS length limit, {{variable}} regex
│   │   │   │   ├── PaginationConstants.java       — default page/size, max size 100
│   │   │   │   └── EventActors.java               — API | DISPATCHER | REAPER | ADMIN
│   │   │   │
│   │   │   ├── controllers/
│   │   │   │   ├── TemplateController.java        — /api/v1/tenant/templates
│   │   │   │   ├── ChannelController.java         — /api/v1/tenant/channels
│   │   │   │   ├── ApiKeyController.java          — /api/v1/tenant/api-keys
│   │   │   │   ├── PlatformAdminController.java   [H2-5]   /api/v1/admin/tenants
│   │   │   │   ├── NotificationController.java    [H8-11]  /api/v1/notifications (X-API-Key)
│   │   │   │   └── ReportController.java          [H19-21]
│   │   │   │
│   │   │   ├── dtos/                              — Java records only
│   │   │   │   ├── CreateTemplateRequest.java, UpdateTemplateRequest.java
│   │   │   │   ├── TemplateResponse.java
│   │   │   │   ├── TemplatePreviewRequest.java, TemplatePreviewResponse.java
│   │   │   │   ├── ChannelConfigResponse.java, UpdateChannelConfigRequest.java
│   │   │   │   ├── CreateApiKeyRequest.java, ApiKeyCreateResponse.java, ApiKeyResponse.java
│   │   │   │   ├── ApiKeyAuthentication.java      — internal auth result, never serialised
│   │   │   │   ├── PageResponse.java              — generic paginated response
│   │   │   │   ├── CreateTenantRequest.java, UpdateTenantRequest.java, TenantResponse.java  [H2-5]
│   │   │   │   └── SendRequest.java, SendResponse.java, NotificationDetailResponse.java     [H8-11]
│   │   │   │
│   │   │   ├── exceptions/
│   │   │   │   ├── GlobalExceptionHandler.java    — RFC 7807 ProblemDetail for every error
│   │   │   │   ├── EntityNotFoundException.java   — 404 (also cross-tenant access)
│   │   │   │   ├── ConflictException.java         — 409
│   │   │   │   ├── MissingVariableException.java  — 400, carries variableName
│   │   │   │   └── SmsBodyTooLongException.java   — 400
│   │   │   │
│   │   │   ├── models/                            — JPA entities
│   │   │   │   ├── Tenant.java                    — @Version optimistic locking
│   │   │   │   ├── AppUser.java
│   │   │   │   ├── ApiKey.java
│   │   │   │   ├── ChannelConfig.java             — settings: jsonb
│   │   │   │   ├── GlobalChannelLimit.java
│   │   │   │   ├── PlatformSetting.java
│   │   │   │   ├── Template.java
│   │   │   │   ├── Notification.java              — domain object AND queue row
│   │   │   │   ├── DeliveryAttempt.java
│   │   │   │   ├── NotificationEvent.java         — audit trail
│   │   │   │   ├── InAppMessage.java
│   │   │   │   └── enums/
│   │   │   │       ├── Channel.java               — EMAIL | SMS | PUSH | IN_APP
│   │   │   │       ├── NotificationStatus.java    — 7 states
│   │   │   │       ├── AttemptOutcome.java
│   │   │   │       ├── TenantStatus.java
│   │   │   │       ├── Role.java                  — PLATFORM_ADMIN | TENANT_ADMIN
│   │   │   │       ├── ApiKeyStatus.java
│   │   │   │       └── AuthMethod.java            — BASIC | API_KEY
│   │   │   │
│   │   │   ├── repositories/                      — every tenant-scoped query takes tenantId
│   │   │   │   ├── TenantRepository.java
│   │   │   │   ├── AppUserRepository.java
│   │   │   │   ├── ApiKeyRepository.java
│   │   │   │   ├── ChannelConfigRepository.java
│   │   │   │   ├── TemplateRepository.java
│   │   │   │   ├── GlobalChannelLimitRepository.java   [H16-19]
│   │   │   │   ├── PlatformSettingRepository.java      [H2-5]
│   │   │   │   ├── NotificationRepository.java         [H8-11]  incl. SKIP LOCKED claim query
│   │   │   │   ├── DeliveryAttemptRepository.java      [H11-16]
│   │   │   │   └── NotificationEventRepository.java    [H8-11]
│   │   │   │
│   │   │   ├── security/
│   │   │   │   ├── ApiKeyAuthFilter.java          — OncePerRequestFilter on /api/v1/notifications/**
│   │   │   │   ├── TenantPrincipal.java           — one principal for Basic and API key auth
│   │   │   │   └── CurrentTenant.java             — resolve tenantId from the SecurityContext
│   │   │   │
│   │   │   ├── services/
│   │   │   │   ├── TemplateService.java           — versioning: edit = new version
│   │   │   │   ├── TemplateRenderer.java          — {{var}} substitution, HTML escape, SMS length
│   │   │   │   ├── ChannelConfigService.java
│   │   │   │   ├── ApiKeyService.java             — issue / list / revoke / authenticate
│   │   │   │   ├── AppUserDetailsService.java     — HTTP Basic user lookup
│   │   │   │   ├── NotificationStateMachine.java  — transition whitelist (pure static)
│   │   │   │   ├── TenantService.java                  [H2-5]
│   │   │   │   ├── GlobalLimitService.java             [H16-19]
│   │   │   │   ├── NotificationIngestionService.java   [H8-11]
│   │   │   │   ├── RecipientValidator.java             [H8-11]
│   │   │   │   ├── ReportService.java                  [H19-21]
│   │   │   │   ├── dispatch/                           [H11-16]
│   │   │   │   │   ├── DispatchScheduler.java     — tick loop (scheduled OR manual)
│   │   │   │   │   ├── FairTenantSelector.java    — weighted round-robin
│   │   │   │   │   ├── WorkClaimer.java           — SKIP LOCKED batch claim
│   │   │   │   │   ├── ChannelWorkerPools.java    — map of Channel → ThreadPoolExecutor
│   │   │   │   │   ├── DeliveryWorker.java        — Runnable: send + record
│   │   │   │   │   ├── OutcomeRecorder.java       — fenced write + attempt + event
│   │   │   │   │   └── LeaseReaper.java           — recovers stuck PROCESSING rows
│   │   │   │   ├── ratelimit/                          [H16-19]
│   │   │   │   │   ├── TokenBucket.java
│   │   │   │   │   └── RateLimiterRegistry.java
│   │   │   │   ├── retry/                              [H16-19]
│   │   │   │   │   ├── RetryPolicy.java           — interface
│   │   │   │   │   ├── ExponentialBackoffWithJitter.java
│   │   │   │   │   └── FailureClassifier.java
│   │   │   │   └── provider/                           [H11-16]
│   │   │   │       ├── ChannelSender.java         — interface (strategy)
│   │   │   │       ├── SendResult.java            — sealed: Success | Transient | Permanent
│   │   │   │       ├── OutboundMessage.java       — record
│   │   │   │       ├── ChannelSenderRegistry.java
│   │   │   │       ├── MockEmailSender.java, MockSmsSender.java, MockPushSender.java
│   │   │   │       └── InAppSender.java
│   │   │   │
│   │   │   └── utils/
│   │   │       ├── Hashing.java                   — SHA-256 hex
│   │   │       └── RequestHasher.java             — idempotency request fingerprint
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           ├── V001__create_tenant.sql
│   │           ├── V002__create_app_user.sql
│   │           ├── V003__create_api_key.sql
│   │           ├── V004__create_global_channel_limit.sql
│   │           ├── V005__create_platform_setting.sql
│   │           ├── V006__create_channel_config.sql
│   │           ├── V007__create_template.sql
│   │           ├── V008__create_notification.sql
│   │           ├── V009__create_delivery_attempt.sql
│   │           ├── V010__create_notification_event.sql
│   │           ├── V011__create_in_app_message.sql
│   │           └── V099__seed_dev_data.sql       — dev users (password123), Acme test API key
│   │
│   └── test/
│       ├── resources/
│       │   └── application-test.yml           — auto-start off, small pools, fast retries
│       └── java/com/assignment/notificationservice/
│           ├── BaseIntegrationTest.java           — singleton Testcontainers PG + tenant helpers
│           ├── ApplicationSmokeTest.java          — context, migrations, seed data, jsonb
│           ├── support/
│           │   ├── MutableClock.java              — settable/advanceable Clock
│           │   ├── TestClockConfig.java           — swaps MutableClock in as the Clock bean
│           │   ├── TestTenant.java                — credentials for a test-created tenant
│           │   └── ProgrammableSender.java        [H11-16] scripted per-notification results
│           ├── unit/
│           │   ├── NotificationStateMachineTest.java
│           │   ├── GlobalExceptionHandlerTest.java
│           │   ├── TemplateRendererTest.java
│           │   ├── RequestHasherTest.java
│           │   ├── ExponentialBackoffWithJitterTest.java   [H16-19]
│           │   ├── TokenBucketTest.java                    [H16-19]
│           │   ├── FairTenantSelectorTest.java             [H16-19]
│           │   └── FailureClassifierTest.java              [H16-19]
│           └── integration/
│               ├── TemplateApiTest.java
│               ├── ChannelConfigApiTest.java
│               ├── ApiKeyApiTest.java
│               ├── RbacIsolationTest.java
│               ├── TenantApiTest.java                      [H2-5]
│               ├── IngestionIdempotencyTest.java           [H8-11]
│               ├── DispatcherHappyPathTest.java            [H11-16]
│               ├── ConcurrentClaimTest.java                [H11-16]
│               ├── LeaseRecoveryTest.java                  [H11-16]
│               ├── RetryFlowTest.java                      [H16-19]
│               ├── RateLimitTest.java                      [H16-19]
│               └── FairnessTest.java                       [H16-19]
└── http/                                                   [H21-23]
    ├── 01-platform-admin.http
    ├── 02-tenant-admin.http
    ├── 03-send-notifications.http
    └── 04-reports.http
```

**Where new code goes:**

| Kind of class | Package |
|---|---|
| REST endpoint | `controllers` |
| Business logic / Spring `@Service` | `services` (sub-packages for dispatch, ratelimit, retry, provider) |
| Spring Data repository | `repositories` |
| JPA entity | `models` |
| Enum | `models.enums` |
| Request / response record | `dtos` |
| Exception or exception mapping | `exceptions` |
| `@Configuration` / `@ConfigurationProperties` | `configs` |
| Shared literal (path, header, limit) | `constants` |
| Stateless static helper | `utils` |
| Filter, principal, tenant resolution | `security` |

---

## 2. Core entities — field-level specification

Every entity maps 1:1 to a JPA `@Entity`. All IDs are `UUID`, generated via `UUID.randomUUID()` in Java
(not DB-generated, so the caller knows the ID before the INSERT completes — important for idempotency).

### 2.1 Tenant

```java
@Entity @Table(name = "tenant")
public class Tenant {
    @Id                         private UUID id;
    @Column(unique = true)      private String name;
    @Column(unique = true)      private String slug;            // lowercase, URL-safe
    @Enumerated(STRING)         private TenantStatus status;    // ACTIVE | SUSPENDED
    private int rateLimitPerSec;   // token bucket: sustained rate
    private int burst;             // token bucket: max burst
    private int weight;            // fairness weight, default 1
    private int maxAttempts;       // retry budget, default 5
    private Instant createdAt;
    private Instant updatedAt;
    @Version                    private Long version;           // optimistic locking
}
```

**Invariants enforced in service layer:**
- `rateLimitPerSec` ≤ platform setting `maxTenantRatePerSec`
- `burst` ≥ `rateLimitPerSec`
- `weight` ∈ [1, 10]
- `slug` matches `^[a-z0-9-]+$`

### 2.2 AppUser

```java
@Entity @Table(name = "app_user")
public class AppUser {
    @Id                         private UUID id;
    @Column(unique = true)      private String username;
    private String passwordHash;    // BCrypt
    @Enumerated(STRING)         private Role role;              // PLATFORM_ADMIN | TENANT_ADMIN
    @ManyToOne(fetch = LAZY)    private Tenant tenant;          // null for PLATFORM_ADMIN
    private Instant createdAt;
}
```

### 2.3 ApiKey

```java
@Entity @Table(name = "api_key")
public class ApiKey {
    @Id                         private UUID id;
    @ManyToOne(fetch = LAZY)    private Tenant tenant;
    private String prefix;          // first 8 chars, for lookup: WHERE prefix = ?
    private String keyHash;         // SHA-256 of full key
    @Enumerated(STRING)         private ApiKeyStatus status;    // ACTIVE | REVOKED
    private Instant createdAt;
    private Instant lastUsedAt;
}
```

**Auth flow:** request header `X-API-Key: ntfy_abcd1234_<random>` → extract prefix `abcd1234` →
find row by prefix → SHA-256 the full key → compare to `keyHash` → resolve `tenant`.

### 2.4 GlobalChannelLimit

```java
@Entity @Table(name = "global_channel_limit")
public class GlobalChannelLimit {
    @Id @Enumerated(STRING)     private Channel channel;        // EMAIL | SMS | PUSH | IN_APP
    private int ratePerSec;
    private int burst;
}
```

### 2.5 PlatformSetting

Simple key-value (or single-row entity):

```java
@Entity @Table(name = "platform_setting")
public class PlatformSetting {
    @Id                         private String key;
    private String value;
}
// Keys: max_tenant_rate_per_sec, default_max_attempts
```

### 2.6 ChannelConfig

```java
@Entity @Table(name = "channel_config",
    uniqueConstraints = @UniqueConstraint(columns = {"tenant_id", "channel"}))
public class ChannelConfig {
    @Id                         private UUID id;
    @ManyToOne(fetch = LAZY)    private Tenant tenant;
    @Enumerated(STRING)         private Channel channel;
    private boolean enabled;
    @Column(columnDefinition = "jsonb")
    private String settings;        // {"fromAddress": "...", "senderId": "..."}
}
```

### 2.7 Template

```java
@Entity @Table(name = "template",
    uniqueConstraints = @UniqueConstraint(columns = {"tenant_id", "code", "channel", "version"}))
public class Template {
    @Id                         private UUID id;
    @ManyToOne(fetch = LAZY)    private Tenant tenant;
    private String code;            // e.g. "order_confirmation"
    @Enumerated(STRING)         private Channel channel;
    private int version;            // auto-incremented per (tenant, code, channel)
    private String subject;         // nullable for SMS/PUSH
    private String body;            // "Hello {{name}}, your order {{orderId}} ..."
    private boolean active;
    private Instant createdAt;
}
```

**Version resolution:** The ingestion service always picks the latest active version:
```sql
SELECT * FROM template
WHERE tenant_id = ? AND code = ? AND channel = ? AND active = true
ORDER BY version DESC LIMIT 1
```

### 2.8 Notification — the central entity

This is both the domain object AND the queue row. No separate queue table.

```java
@Entity @Table(name = "notification",
    uniqueConstraints = @UniqueConstraint(columns = {"tenant_id", "idempotency_key"}))
public class Notification {
    @Id                         private UUID id;

    // ownership
    @ManyToOne(fetch = LAZY)    private Tenant tenant;
    @Enumerated(STRING)         private Channel channel;
    private String recipient;       // email, E.164 phone, device token, user ID

    // idempotency
    private String idempotencyKey;  // from Idempotency-Key header
    private String requestHash;     // SHA-256 of canonical(channel + recipient + templateCode + variables)

    // template snapshot (frozen at accept time)
    @ManyToOne(fetch = LAZY)    private Template template;
    private int templateVersion;
    private String subject;         // rendered
    private String body;            // rendered

    // original variables (kept for audit/debug)
    @Column(columnDefinition = "jsonb")
    private String variables;

    // state machine
    @Enumerated(STRING)         private NotificationStatus status;

    // scheduling + queue
    private Instant scheduledAt;    // null = immediate
    private Instant nextAttemptAt;  // drives the claim query: WHERE next_attempt_at <= now

    // retry
    private int attemptCount;
    private int maxAttempts;

    // lease (claim lock)
    private String lockedBy;        // worker ID (UUID string)
    private Instant lockedUntil;    // lease expiry

    // outcome
    private String lastErrorCode;
    private String failureReason;
    private Instant sentAt;

    // audit
    private Instant createdAt;
    private Instant updatedAt;
}
```

### 2.9 DeliveryAttempt

```java
@Entity @Table(name = "delivery_attempt",
    uniqueConstraints = @UniqueConstraint(columns = {"notification_id", "attempt_no"}))
public class DeliveryAttempt {
    @Id                         private UUID id;
    @ManyToOne(fetch = LAZY)    private Notification notification;
    private int attemptNo;
    private Instant startedAt;
    private Instant finishedAt;
    @Enumerated(STRING)         private AttemptOutcome outcome;  // SUCCESS | TRANSIENT_FAILURE | PERMANENT_FAILURE | ABANDONED
    private String errorCode;
    private String errorMessage;
    private String providerMessageId;
    private long latencyMs;
}
```

### 2.10 NotificationEvent (audit trail)

```java
@Entity @Table(name = "notification_event")
public class NotificationEvent {
    @Id                         private UUID id;
    @ManyToOne(fetch = LAZY)    private Notification notification;
    private UUID tenantId;          // denormalized for reporting queries
    @Enumerated(STRING)         private NotificationStatus fromStatus;
    @Enumerated(STRING)         private NotificationStatus toStatus;
    private String reason;          // "provider_success", "transient_failure:TIMEOUT", "lease_expired", "cancelled_by_user"
    private String actor;           // API | DISPATCHER | REAPER | ADMIN
    private Instant occurredAt;
}
```

### 2.11 InAppMessage

```java
@Entity @Table(name = "in_app_message",
    uniqueConstraints = @UniqueConstraint(columns = {"notification_id"}))
public class InAppMessage {
    @Id                         private UUID id;
    @ManyToOne(fetch = LAZY)    private Tenant tenant;
    private String recipient;
    @OneToOne(fetch = LAZY)     private Notification notification;
    private String title;
    private String body;
    private Instant readAt;         // null = unread
    private Instant createdAt;
}
```

---

## 3. State machine — transitions, guards, and audit

### 3.1 The seven states

```
SCHEDULED   — future send; waiting for scheduledAt
PENDING     — ready to be claimed by a worker
PROCESSING  — claimed; provider call in flight
RETRYING    — transient failure; waiting for backoff to expire
SENT        — terminal: provider accepted the message
FAILED      — terminal: permanent failure or retries exhausted
CANCELLED   — terminal: user cancelled before dispatch
```

### 3.2 Legal transitions (whitelist — everything else is rejected)

```
From         → To           Trigger                        Actor
─────────────────────────────────────────────────────────────────────
null         → SCHEDULED    submit with future scheduledAt  API
null         → PENDING      submit immediate                API
SCHEDULED    → PENDING      scheduledAt reached (implicit)  DISPATCHER
SCHEDULED    → CANCELLED    user cancels                    API
PENDING      → CANCELLED    user cancels                    API
PENDING      → PROCESSING   worker claims the row           DISPATCHER
RETRYING     → PROCESSING   backoff expired, worker claims  DISPATCHER
PROCESSING   → SENT         provider returns Success        DISPATCHER
PROCESSING   → RETRYING     transient failure, attempts left DISPATCHER
PROCESSING   → FAILED       permanent failure OR exhausted  DISPATCHER
PROCESSING   → PENDING      pool rejects (back-pressure)   DISPATCHER
FAILED       → PENDING      manual retry by tenant admin    ADMIN
```

### 3.3 Implementation

```java
public class NotificationStateMachine {

    // Whitelist encoded as a Map<FromStatus, Set<ToStatus>>
    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED =
        Map.of(
            SCHEDULED,  Set.of(PENDING, CANCELLED),
            PENDING,    Set.of(PROCESSING, CANCELLED),
            PROCESSING, Set.of(SENT, RETRYING, FAILED, PENDING),
            RETRYING,   Set.of(PROCESSING),
            FAILED,     Set.of(PENDING)
        );

    /**
     * Validates and applies a transition.
     * @throws IllegalStateException if the transition is not allowed
     */
    public static void transition(Notification n,
                                   NotificationStatus to,
                                   String reason,
                                   String actor,
                                   Clock clock) {
        NotificationStatus from = n.getStatus();
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(
                "Illegal transition: " + from + " → " + to);
        }
        n.setStatus(to);
        n.setUpdatedAt(clock.instant());
        // The caller MUST persist a NotificationEvent in the SAME transaction
    }
}
```

**Rule:** every call to `transition()` must be inside a `@Transactional` method that also
inserts a `NotificationEvent`. No status change without an audit row, ever.

### 3.4 Visual state machine

```
                    ┌──────────────────────────────┐
                    │         SUBMIT API           │
                    └──────┬──────────────┬────────┘
                           │              │
                    scheduledAt?     immediate
                           │              │
                           ▼              ▼
                     ┌──────────┐   ┌──────────┐
                     │SCHEDULED │   │ PENDING  │◄────── manual retry (ADMIN)
                     └────┬─┬───┘   └────┬─┬───┘              ▲
                          │ │            │ │                    │
                 due ─────┘ │   claimed ─┘ │                   │
                          cancel        cancel                 │
                            │              │                   │
                            ▼              ▼                   │
                       ┌──────────┐   ┌──────────┐            │
                       │CANCELLED │   │PROCESSING│────────────┘
                       └──────────┘   └──┬─┬─┬─┬─┘    (pool reject → PENDING)
                                         │ │ │ │
                          success ───────┘ │ │ └─── permanent failure
                          transient ───────┘ │         OR exhausted
                          (attempts left)    │
                                             │
                  ┌──────────┐         ┌──────────┐
                  │ RETRYING │         │  FAILED  │
                  └────┬─────┘         └──────────┘
                       │
                  backoff expired
                  → claimed again
                       │
                       └───────► PROCESSING (loops back)
```

---

## 4. Queue mechanism — DB-as-queue with SKIP LOCKED

### 4.1 Why DB-as-queue (not Kafka/Redis)

The brief says "no distributed systems / microservices." PostgreSQL with `FOR UPDATE SKIP LOCKED`
gives us a safe, multi-consumer work queue inside a single database transaction, with zero
additional infrastructure. The notification table IS the queue — no separate queue table.

### 4.2 The claim query

This is the core SQL that makes the dispatcher work:

```sql
-- Claims up to :k notification rows for a given tenant+channel
-- that are due for processing (next_attempt_at <= now).
--
-- FOR UPDATE: locks the rows for the duration of this transaction
-- SKIP LOCKED: if another worker already locked a row, skip it (no blocking)
--
-- In one atomic statement: select + lock + update status to PROCESSING

WITH due AS (
    SELECT id FROM notification
    WHERE tenant_id = :tenantId
      AND channel = :channel
      AND status IN ('SCHEDULED', 'PENDING', 'RETRYING')
      AND next_attempt_at <= :now
    ORDER BY next_attempt_at ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
UPDATE notification n
SET status = 'PROCESSING',
    locked_by = :workerId,
    locked_until = :now + :leaseDuration,
    attempt_count = attempt_count + 1,
    updated_at = :now
FROM due
WHERE n.id = due.id
RETURNING n.*;
```

**Why this works for concurrency:**
- Two workers calling this at the same time will never get the same row.
- `SKIP LOCKED` means no deadlocks, no waits — workers always make progress.
- The UPDATE is inside the CTE's transaction — the row goes from PENDING → PROCESSING atomically.

### 4.3 Indexes that make the claim fast

```sql
-- Partial index: only indexable rows are those eligible for claiming
CREATE INDEX idx_notification_claim
ON notification (tenant_id, channel, next_attempt_at)
WHERE status IN ('SCHEDULED', 'PENDING', 'RETRYING');

-- For the lease reaper: find PROCESSING rows with expired leases
CREATE INDEX idx_notification_lease
ON notification (status, locked_until)
WHERE status = 'PROCESSING';
```

### 4.4 The dispatch tick loop

```
Every 100ms (configurable):

  1. FairTenantSelector picks the next tenant (round-robin with weights)
  2. For each channel that tenant has enabled:
     a. Ask TokenBucket: how many tokens can we take?
        - n = min(tenant_bucket.tryAcquire(quantum × weight),
                  global_channel_bucket.tryAcquire(k))
     b. If n == 0, skip this tenant×channel
     c. WorkClaimer.claim(tenantId, channel, n) → list of Notification
     d. For each claimed notification:
        - Submit DeliveryWorker to the channel's ThreadPoolExecutor
        - If the pool rejects (queue full): release the row back to PENDING
          (no attempt consumed, no retry penalty)
  3. Move cursor to next tenant for the next tick
```

### 4.5 Fenced outcome writes

After a worker sends a message, it writes the result back. But what if the worker
was slow and the lease expired? The LeaseReaper may have already re-queued the row
and another worker may have claimed it. We MUST NOT let the stale worker overwrite
the new worker's result.

**Fencing condition** on every outcome write:

```sql
UPDATE notification
SET status = :newStatus,
    sent_at = :sentAt,            -- null unless SENT
    next_attempt_at = :nextAt,    -- null unless RETRYING
    last_error_code = :errorCode,
    failure_reason = :reason,
    locked_by = NULL,
    locked_until = NULL,
    updated_at = :now
WHERE id = :id
  AND status = 'PROCESSING'       -- still in the state we expect
  AND locked_by = :workerId        -- OUR lease, not a re-claim
RETURNING *;
```

If `RETURNING` is empty → the row was reaped and re-claimed. The stale worker logs a
warning and discards its result. The provider already dedupes on `notification.id`
(the idempotency key), so no duplicate delivery.

---

## 5. Clock mechanism — deterministic time in tests

### 5.1 The problem

The dispatcher, scheduler, token buckets, backoff delays, lease expiry, and scheduled sends
all depend on "now." If tests use `Instant.now()`, they're non-deterministic (race conditions,
flaky assertions, `Thread.sleep` needed).

### 5.2 The solution: injectable `java.time.Clock`

```java
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();   // production: real time
    }
}
```

Every class that needs "now" injects this `Clock` bean:

```java
@Service
public class NotificationIngestionService {
    private final Clock clock;
    // ...
    public Notification submit(SendRequest req) {
        Instant now = clock.instant();           // ← never Instant.now()
        notification.setCreatedAt(now);
        notification.setNextAttemptAt(
            req.scheduledAt() != null ? req.scheduledAt() : now
        );
        // ...
    }
}
```

### 5.3 Test clock — `MutableClock`

```java
/**
 * A Clock whose instant can be set and advanced manually.
 * Used in tests for deterministic time control.
 */
public class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
        this.zone = ZoneOffset.UTC;
    }

    @Override public Instant instant()   { return instant.get(); }
    @Override public ZoneId getZone()    { return zone; }
    @Override public Clock withZone(ZoneId z) { return this; }

    /** Advance time by the given duration. Returns new instant. */
    public Instant advance(Duration d) {
        return instant.updateAndGet(i -> i.plus(d));
    }

    /** Set time to a specific instant. */
    public void setInstant(Instant i) {
        instant.set(i);
    }
}
```

### 5.4 How tests use MutableClock

```java
@SpringBootTest
class ScheduledSendTest extends BaseIntegrationTest {

    @Autowired MutableClock clock;          // injected via test profile
    @Autowired DispatchScheduler scheduler; // manually triggered in tests
    @Autowired NotificationRepository repo;

    @Test
    void scheduled_notification_not_dispatched_before_its_time() {
        // Arrange: clock at T0, notification scheduled for T0 + 10 min
        Instant t0 = clock.instant();
        Notification n = submitNotification(scheduledAt: t0.plus(10, MINUTES));

        // Act: tick the dispatcher at T0 + 5 min (before scheduled time)
        clock.advance(Duration.ofMinutes(5));
        scheduler.tick();   // ← manual tick, not scheduled

        // Assert: still SCHEDULED, not claimed
        assertThat(repo.findById(n.getId()).get().getStatus()).isEqualTo(SCHEDULED);

        // Act: tick at T0 + 11 min (after scheduled time)
        clock.advance(Duration.ofMinutes(6));
        scheduler.tick();

        // Assert: now PROCESSING or SENT
        await().atMost(2, SECONDS).untilAsserted(() ->
            assertThat(repo.findById(n.getId()).get().getStatus())
                .isIn(PROCESSING, SENT)
        );
    }
}
```

**Key pattern:** In tests, the dispatcher's `@Scheduled` annotation is disabled (via profile).
Instead, tests call `scheduler.tick()` manually after advancing the clock. This gives us
complete control over timing without any `Thread.sleep`.

---

## 6. Mock provider system

### 6.1 Interface

```java
public interface ChannelSender {
    Channel channel();
    SendResult send(OutboundMessage msg);
}

public record OutboundMessage(
    String idempotencyKey,     // = notification.id.toString()
    String recipient,
    String subject,            // null for SMS/PUSH
    String body,
    Channel channel,
    Map<String, String> metadata   // tenant slug, template code, etc.
) {}

public sealed interface SendResult {
    record Success(String providerMessageId)             implements SendResult {}
    record Transient(String errorCode, String message)   implements SendResult {}
    record Permanent(String errorCode, String message)   implements SendResult {}
}
```

### 6.2 Mock sender — configurable failure + latency + idempotency

```java
@Component
public class MockEmailSender implements ChannelSender {

    private final MockProviderProperties props;     // from application.yml
    private final Clock clock;
    private final Random random;

    // Idempotency: tracks which notification IDs we've already "sent"
    private final ConcurrentHashMap<String, String> sentIds = new ConcurrentHashMap<>();

    @Override
    public Channel channel() { return Channel.EMAIL; }

    @Override
    public SendResult send(OutboundMessage msg) {
        // Simulate network latency
        sleep(props.getLatencyMs());

        // Idempotency: if we've seen this ID before, return the same result
        String existing = sentIds.get(msg.idempotencyKey());
        if (existing != null) {
            return new SendResult.Success(existing);   // deduplicated
        }

        // Simulate failures
        double roll = random.nextDouble();
        if (roll < props.getPermanentFailureRate()) {
            return new SendResult.Permanent("INVALID_RECIPIENT",
                "Mailbox does not exist");
        }
        if (roll < props.getPermanentFailureRate() + props.getTransientFailureRate()) {
            return new SendResult.Transient("TIMEOUT",
                "Provider timed out");
        }

        // Success
        String providerMsgId = "mock-email-" + UUID.randomUUID();
        sentIds.put(msg.idempotencyKey(), providerMsgId);
        return new SendResult.Success(providerMsgId);
    }
}
```

### 6.3 Test-specific mock overrides

For integration tests, we don't use the random-failure mock. We inject deterministic mocks:

```java
/**
 * A ChannelSender that can be programmed per-notification.
 * Default: always succeed. Override per ID for failure scenarios.
 */
public class ProgrammableSender implements ChannelSender {

    private final Channel channel;
    private final Map<String, Queue<SendResult>> programmed = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger();

    /** Program: the next N calls for this idempotency key return these results in order */
    public void program(String idempotencyKey, SendResult... results) {
        programmed.put(idempotencyKey, new ConcurrentLinkedQueue<>(List.of(results)));
    }

    @Override
    public SendResult send(OutboundMessage msg) {
        callCount.incrementAndGet();
        Queue<SendResult> queue = programmed.get(msg.idempotencyKey());
        if (queue != null && !queue.isEmpty()) {
            return queue.poll();
        }
        return new SendResult.Success("mock-" + UUID.randomUUID());
    }

    public int getCallCount() { return callCount.get(); }
    public void reset() { programmed.clear(); callCount.set(0); }
}
```

### 6.4 Test scenario examples

| Scenario | Setup | Assertion |
|---|---|---|
| Happy path | Default ProgrammableSender (always success) | Status = SENT, 1 attempt, events: PENDING→PROCESSING→SENT |
| Transient then success | `program(id, Transient("TIMEOUT"), Transient("TIMEOUT"), Success("ok"))` | Status = SENT, 3 attempts, backoff spacing correct |
| Retries exhausted | `program(id, Transient × maxAttempts)` | Status = FAILED, reason = RETRIES_EXHAUSTED, attempts = max |
| Permanent failure | `program(id, Permanent("INVALID"))` | Status = FAILED, 1 attempt, no retry |
| Lease expired + fencing | Sender that blocks for longer than lease duration | Reaper re-queues; stale write rejected; final status from re-claimed worker |

---

## 7. Token bucket — rate limiting

### 7.1 Implementation

```java
public class TokenBucket {
    private final double ratePerSec;       // sustained refill rate
    private final int burst;               // max tokens
    private double tokens;                 // current tokens (fractional for precision)
    private Instant lastRefill;
    private final Clock clock;

    public TokenBucket(int ratePerSec, int burst, Clock clock) {
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.tokens = burst;               // start full
        this.lastRefill = clock.instant();
        this.clock = clock;
    }

    /**
     * Try to acquire up to `requested` tokens.
     * Returns the number actually granted (0 to requested).
     * Never blocks.
     */
    public synchronized int tryAcquire(int requested) {
        refill();
        int granted = (int) Math.min(requested, tokens);
        tokens -= granted;
        return granted;
    }

    private void refill() {
        Instant now = clock.instant();
        double elapsed = Duration.between(lastRefill, now).toNanos() / 1_000_000_000.0;
        tokens = Math.min(burst, tokens + elapsed * ratePerSec);
        lastRefill = now;
    }
}
```

### 7.2 Two-layer check at claim time

```java
// In DispatchScheduler.tick():
int wantedQuantum = BASE_QUANTUM * tenant.getWeight();

// Layer 1: tenant's own rate limit
int tenantGranted = tenantBucket.tryAcquire(wantedQuantum);

// Layer 2: global channel rate limit (shared across all tenants)
int channelGranted = channelBucket.tryAcquire(tenantGranted);

// channelGranted is the actual batch size for the claim query
if (channelGranted > 0) {
    List<Notification> claimed = workClaimer.claim(tenantId, channel, channelGranted);
    // submit to pool...
}
```

Tokens are taken BEFORE claiming, so every claimed row is guaranteed to be within rate limits.

### 7.3 Testing with MutableClock

```java
@Test
void bucket_respects_rate_and_burst() {
    MutableClock clock = new MutableClock(Instant.now());
    TokenBucket bucket = new TokenBucket(10, 20, clock);   // 10/s, burst 20

    // Initially full: burst = 20
    assertThat(bucket.tryAcquire(20)).isEqualTo(20);
    assertThat(bucket.tryAcquire(1)).isEqualTo(0);         // empty

    // After 1 second: 10 tokens refilled
    clock.advance(Duration.ofSeconds(1));
    assertThat(bucket.tryAcquire(15)).isEqualTo(10);       // only 10 available

    // After 5 seconds: would be 50, capped at burst 20
    clock.advance(Duration.ofSeconds(5));
    assertThat(bucket.tryAcquire(25)).isEqualTo(20);       // capped at burst
}
```

---

## 8. Fairness — weighted round-robin

### 8.1 Problem

Tenant A has 100,000 queued notifications. Tenant B has 10.
Without fairness, A saturates every tick and B waits until A is done.

### 8.2 Solution: deficit-style weighted round-robin

```java
public class FairTenantSelector {
    private int cursorIndex = 0;    // persists across ticks

    /**
     * Returns an ordered list of (tenantId, quantum) pairs for this tick.
     * Each tenant gets BASE_QUANTUM × weight notifications per round.
     * The cursor advances so the same tenant isn't always first.
     */
    public List<TenantWork> selectForTick(List<TenantWithWeight> tenantsWithDueWork) {
        if (tenantsWithDueWork.isEmpty()) return List.of();

        // Rotate to continue from where we left off
        int n = tenantsWithDueWork.size();
        List<TenantWork> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            var tenant = tenantsWithDueWork.get((cursorIndex + i) % n);
            int quantum = BASE_QUANTUM * tenant.weight();
            result.add(new TenantWork(tenant.id(), quantum));
        }
        cursorIndex = (cursorIndex + 1) % n;
        return result;
    }
}
```

### 8.3 Fairness test

```java
@Test
void small_tenant_finishes_before_large_tenant_is_half_done() {
    // Tenant A: weight 1, 2000 queued notifications
    // Tenant B: weight 1, 20 queued notifications
    // Expected: B finishes ALL 20 within the first few ticks,
    //           while A has processed only ~20 (same quantum per tick)

    submitNotifications(tenantA, 2000);
    submitNotifications(tenantB, 20);

    // Run enough ticks to send ~40 per tenant
    for (int i = 0; i < 10; i++) {
        scheduler.tick();
        clock.advance(Duration.ofMillis(100));
    }

    await().atMost(5, SECONDS).untilAsserted(() -> {
        long bSent = countByStatus(tenantB, SENT);
        long aTotal = countByStatus(tenantA, SENT) + countByStatus(tenantA, PROCESSING);
        assertThat(bSent).isEqualTo(20);                // B fully done
        assertThat(aTotal).isLessThan(200);              // A barely started
    });
}
```

---

## 9. Test infrastructure

### 9.1 BaseIntegrationTest

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notify_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected MutableClock clock;

    // Helper: create tenant, template, API key and return usable credentials
    protected TestTenant setupTenant(String slug) { ... }

    // Helper: submit a notification and return its ID
    protected UUID sendNotification(TestTenant tenant, String templateCode,
                                     String recipient, Map<String, String> vars) { ... }
}
```

### 9.2 Test profile (application-test.yml)

```yaml
spring:
  datasource:
    # Overridden by @DynamicPropertySource from Testcontainers
  flyway:
    enabled: true

notify:
  dispatcher:
    auto-start: false           # ← no scheduled ticks; tests call tick() manually
    tick-interval: 100ms
    base-quantum: 5             # smaller for test determinism
    lease-duration: 5s          # short lease so reaper tests don't wait long
    reaper-interval: 1s
  pools:
    EMAIL:  { core: 2, max: 2, queue: 20 }
    SMS:    { core: 2, max: 2, queue: 20 }
    PUSH:   { core: 2, max: 2, queue: 20 }
    IN_APP: { core: 1, max: 1, queue: 10 }
  retry:
    base-delay: 1s              # faster for tests
    max-delay: 10s
    default-max-attempts: 3
  mock-providers:
    # In test profile, ProgrammableSender beans replace these
    EMAIL: { latency-ms: 0, transient-failure-rate: 0, permanent-failure-rate: 0 }
```

### 9.3 Key testing patterns

**Pattern 1: Manual ticks with MutableClock**
- Disable `@Scheduled` in test profile (`auto-start: false`)
- Test calls `scheduler.tick()` explicitly
- Advance clock with `clock.advance(Duration.ofSeconds(n))` between ticks
- Assertions are deterministic: we know exactly what time it is

**Pattern 2: Awaitility for async worker completion**
- After `tick()`, workers run on the channel thread pools
- Use `await().atMost(2, SECONDS).untilAsserted(...)` to wait for completion
- Never use `Thread.sleep` — Awaitility polls and fails fast

**Pattern 3: ProgrammableSender for deterministic failures**
- Inject `ProgrammableSender` beans in test profile
- Program exact sequences: `program(notifId, Transient, Transient, Success)`
- Assert exact attempt counts, backoff spacing, state transitions

**Pattern 4: Concurrent stress with CountDownLatch**
- For idempotency tests: N threads all submit the same idempotency key simultaneously
- For claim tests: N threads all call `workClaimer.claim()` simultaneously
- Assert: exactly 1 row created / each row claimed exactly once

---

## 10. Flyway migration order

All migrations run in a single transaction. Schema is ready before the first test.

| File | Creates | Key constraints |
|---|---|---|
| V001 | `tenant` | `UNIQUE(slug)` |
| V002 | `app_user` | `UNIQUE(username)`, FK → tenant |
| V003 | `api_key` | FK → tenant |
| V004 | `global_channel_limit` | PK = channel enum |
| V005 | `platform_setting` | PK = key string |
| V006 | `channel_config` | `UNIQUE(tenant_id, channel)`, FK → tenant |
| V007 | `template` | `UNIQUE(tenant_id, code, channel, version)`, FK → tenant |
| V008 | `notification` | `UNIQUE(tenant_id, idempotency_key)`, FK → tenant + template, partial indexes for claim + lease |
| V009 | `delivery_attempt` | `UNIQUE(notification_id, attempt_no)`, FK → notification |
| V010 | `notification_event` | FK → notification, indexed on (notification_id, occurred_at) |
| V011 | `in_app_message` | `UNIQUE(notification_id)`, FK → tenant + notification |
| V099 | Seed data (dev only) | Platform admin, 2 tenants, templates, API keys |

---

## 11. Configuration reference (application.yml)

```yaml
spring:
  application:
    name: notification-service
  datasource:
    url: jdbc:postgresql://localhost:5432/notify
    username: notify
    password: notify
  jpa:
    hibernate:
      ddl-auto: validate         # Flyway owns the schema
    open-in-view: false
    properties:
      hibernate:
        jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration

notify:
  dispatcher:
    auto-start: true
    tick-interval: 100ms
    base-quantum: 20
    lease-duration: 60s
    reaper-interval: 30s
  pools:
    EMAIL:  { core: 8, max: 8, queue: 200 }
    SMS:    { core: 4, max: 4, queue: 100 }
    PUSH:   { core: 8, max: 8, queue: 200 }
    IN_APP: { core: 2, max: 2, queue: 100 }
  retry:
    base-delay: 2s
    max-delay: 5m
    default-max-attempts: 5
  mock-providers:
    EMAIL: { latency-ms: 50,  transient-failure-rate: 0.10, permanent-failure-rate: 0.02 }
    SMS:   { latency-ms: 80,  transient-failure-rate: 0.15, permanent-failure-rate: 0.02 }
    PUSH:  { latency-ms: 30,  transient-failure-rate: 0.05, permanent-failure-rate: 0.01 }
```

---

## 12. First commit checklist (H0–2 deliverable)

After this block, running `./gradlew test` must pass with:
- [x] Spring context loads with Testcontainers Postgres
- [x] All 11 Flyway migrations apply cleanly
- [x] Clock bean is injectable and MutableClock works in tests
- [x] ProblemDetail handler returns RFC 7807 JSON for validation errors
- [x] Seed data loads: platform admin can log in, tenants exist

```bash
git add -A && git commit -m "chore: project skeleton, Flyway schema, test infra"
```

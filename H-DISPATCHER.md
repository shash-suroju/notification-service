# H-DISPATCHER — Dispatcher Core + Retry + Rate Limits + Fairness

> Claude Code: read this file and CLAUDE.md (for rules).
> This is the HARDEST and MOST IMPORTANT block. Every evaluator will spend 80% of their
> review time here. Get this right and the submission is strong even if nothing else is added.
>
> Prerequisites: H0-2 (skeleton), H-TEMPLATES (templates, channels, API keys, RBAC),
> and H-INGESTION (notification ingestion with idempotency) are complete.

---

## IMPORTANT: Repo package structure

Flat packages — NO new sub-packages:

```
models/          ← JPA entities
configs/         ← @Configuration, @ConfigurationProperties, executor beans
controllers/     ← @RestController
dtos/            ← request/response records, SendResult, OutboundMessage
exceptions/      ← custom exceptions
constants/       ← enums (Channel, NotificationStatus, etc.)
repositories/    ← JPA repositories
security/        ← auth filters, TenantPrincipal, CurrentTenant
services/        ← all business logic: dispatcher, claimer, workers, senders, reaper
utils/           ← pure utilities: TokenBucket, BackoffCalculator, FailureClassifier
```

---

## What this block delivers

After this block, notifications actually get SENT:

1. **DispatchScheduler** — tick loop drives the entire dispatch pipeline
2. **FairTenantSelector** — weighted round-robin so no tenant starves another
3. **WorkClaimer** — `SELECT FOR UPDATE SKIP LOCKED` claims rows atomically
4. **ChannelWorkerPools** — bounded ThreadPoolExecutor per channel with back-pressure
5. **DeliveryWorker** — calls mock provider, records outcome
6. **OutcomeRecorder** — fenced conditional write (locked_by guard)
7. **LeaseReaper** — recovers stuck PROCESSING rows with expired leases
8. **TokenBucket** — per-tenant + per-channel rate limiting (two layers)
9. **RateLimiterRegistry** — manages all token buckets
10. **ExponentialBackoffWithJitter** — retry delay calculation
11. **FailureClassifier** — transient vs permanent failure mapping
12. **Mock providers** — configurable latency + failure rates + idempotent dedup
13. **ProgrammableSender** — deterministic test mock
14. **ChannelSender interface** + **SendResult sealed type** + **OutboundMessage record**
15. **Integration tests** — happy path, concurrent claim, retry, lease recovery, rate limit, fairness

Commit message:
```
feat: dispatcher with retry, rate limits, fairness

- DispatchScheduler tick loop with manual tick for tests
- WorkClaimer: SELECT FOR UPDATE SKIP LOCKED batch claim
- Per-channel bounded worker pools with back-pressure
- Fenced outcome writes (locked_by guard prevents stale overwrites)
- LeaseReaper recovers stuck PROCESSING rows
- ExponentialBackoffWithJitter retry policy
- FailureClassifier: transient vs permanent
- TokenBucket rate limiting (tenant + global channel, two layers)
- FairTenantSelector: weighted round-robin with cursor
- Mock providers with configurable failure rates
- ProgrammableSender for deterministic test scenarios
- Integration tests: concurrent claim, retry flows, lease recovery, fairness
```

---

## 1. Interfaces and sealed types

### SendResult

**Location:** `dtos/SendResult.java`

```java
public sealed interface SendResult {

    record Success(String providerMessageId) implements SendResult {}

    record TransientFailure(String errorCode, String message) implements SendResult {}

    record PermanentFailure(String errorCode, String message) implements SendResult {}
}
```

### OutboundMessage

**Location:** `dtos/OutboundMessage.java`

```java
public record OutboundMessage(
    String idempotencyKey,          // = notification.id.toString()
    String recipient,
    String subject,                 // null for SMS/PUSH
    String body,
    Channel channel,
    String tenantSlug               // for logging/metrics
) {}
```

### ChannelSender

**Location:** `services/ChannelSender.java`

```java
public interface ChannelSender {
    Channel channel();
    SendResult send(OutboundMessage msg);
}
```

---

## 2. Mock providers

### MockEmailSender

**Location:** `services/MockEmailSender.java`

```java
@Component
@Profile("!test")
public class MockEmailSender implements ChannelSender {

    private final MockProviderProperties.ChannelMockConfig config;
    private final Random random = new Random();

    // Idempotency: remembers which notification IDs were already "sent"
    private final ConcurrentHashMap<String, String> sentIds = new ConcurrentHashMap<>();

    // Inject the EMAIL-specific config from MockProviderProperties

    @Override
    public Channel channel() { return Channel.EMAIL; }

    @Override
    public SendResult send(OutboundMessage msg) {
        // 1. Simulate latency
        if (config.getLatencyMs() > 0) {
            try { Thread.sleep(config.getLatencyMs()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // 2. Idempotency: if already sent, return same success
        String existing = sentIds.get(msg.idempotencyKey());
        if (existing != null) {
            return new SendResult.Success(existing);
        }

        // 3. Simulate failures
        double roll = random.nextDouble();
        if (roll < config.getPermanentFailureRate()) {
            return new SendResult.PermanentFailure("INVALID_RECIPIENT",
                "Mailbox does not exist");
        }
        if (roll < config.getPermanentFailureRate() + config.getTransientFailureRate()) {
            return new SendResult.TransientFailure("TIMEOUT", "Provider timed out");
        }

        // 4. Success
        String providerMsgId = "mock-email-" + UUID.randomUUID();
        sentIds.put(msg.idempotencyKey(), providerMsgId);
        return new SendResult.Success(providerMsgId);
    }
}
```

### MockSmsSender

**Location:** `services/MockSmsSender.java`

Same pattern as MockEmailSender but:
- `channel()` returns `Channel.SMS`
- Error codes: `CARRIER_REJECTED` for permanent, `THROTTLED` for transient
- Provider ID prefix: `mock-sms-`

### MockPushSender

**Location:** `services/MockPushSender.java`

Same pattern but:
- `channel()` returns `Channel.PUSH`
- Error codes: `INVALID_TOKEN` for permanent, `SERVICE_UNAVAILABLE` for transient
- Provider ID prefix: `mock-push-`

### InAppSender

**Location:** `services/InAppSender.java`

```java
@Component
public class InAppSender implements ChannelSender {

    private final InAppMessageRepository inAppMessageRepository;

    @Override
    public Channel channel() { return Channel.IN_APP; }

    @Override
    public SendResult send(OutboundMessage msg) {
        // In-app "sending" = writing a row to the in_app_message table.
        // The UNIQUE(notification_id) constraint provides idempotency.
        try {
            InAppMessage inApp = new InAppMessage();
            // set tenant, recipient, notification, title (subject), body from msg
            // title = msg.subject(), body = msg.body()
            // notification_id parsed from msg.idempotencyKey() (it's the notification UUID)
            inAppMessageRepository.save(inApp);
            return new SendResult.Success("inapp-" + inApp.getId());
        } catch (DataIntegrityViolationException e) {
            // Already delivered (unique constraint on notification_id)
            return new SendResult.Success("inapp-duplicate");
        }
    }
}
```

**Note on InAppSender:** It needs the Notification entity reference to set the FK.
You have two choices:
1. Pass the notification ID in `OutboundMessage` and look it up
2. Store notification reference in a `ThreadLocal` or context object

Simplest approach: add `UUID notificationId` field to `OutboundMessage` and use
`notificationRepository.getReferenceById(id)` in InAppSender to get a lazy proxy
without a DB hit.

Update OutboundMessage:
```java
public record OutboundMessage(
    UUID notificationId,            // the notification's PK
    String idempotencyKey,          // = notificationId.toString()
    String recipient,
    String subject,
    String body,
    Channel channel,
    String tenantSlug
) {}
```

### ChannelSenderRegistry

**Location:** `services/ChannelSenderRegistry.java`

```java
@Component
public class ChannelSenderRegistry {

    private final Map<Channel, ChannelSender> senders;

    public ChannelSenderRegistry(List<ChannelSender> allSenders) {
        this.senders = allSenders.stream()
            .collect(Collectors.toMap(ChannelSender::channel, Function.identity()));
    }

    public ChannelSender get(Channel channel) {
        ChannelSender sender = senders.get(channel);
        if (sender == null) {
            throw new IllegalArgumentException("No sender registered for channel: " + channel);
        }
        return sender;
    }
}
```

---

## 3. ProgrammableSender (test only)

**Location:** `src/test/java/.../support/ProgrammableSender.java`

```java
public class ProgrammableSender implements ChannelSender {

    private final Channel channel;
    private final ConcurrentHashMap<String, Queue<SendResult>> programmed = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final List<String> callLog = Collections.synchronizedList(new ArrayList<>());

    public ProgrammableSender(Channel channel) {
        this.channel = channel;
    }

    @Override
    public Channel channel() { return channel; }

    /**
     * Program: next calls for this idempotency key return these results in order.
     * After the sequence is exhausted, returns Success by default.
     */
    public void program(String idempotencyKey, SendResult... results) {
        programmed.put(idempotencyKey, new ConcurrentLinkedQueue<>(List.of(results)));
    }

    /**
     * Program: ALL calls return this result (for blanket failure/success scenarios).
     */
    public void programDefault(SendResult result) {
        programmed.put("__default__", new ConcurrentLinkedQueue<>(
            Collections.nCopies(10000, result)));
    }

    @Override
    public SendResult send(OutboundMessage msg) {
        callCount.incrementAndGet();
        callLog.add(msg.idempotencyKey());

        // Check programmed sequence for this specific key
        Queue<SendResult> queue = programmed.get(msg.idempotencyKey());
        if (queue != null && !queue.isEmpty()) {
            return queue.poll();
        }

        // Check default
        Queue<SendResult> defaultQueue = programmed.get("__default__");
        if (defaultQueue != null && !defaultQueue.isEmpty()) {
            return defaultQueue.poll();
        }

        // Default: success
        return new SendResult.Success("mock-" + channel.name().toLowerCase() + "-" + UUID.randomUUID());
    }

    public int getCallCount() { return callCount.get(); }
    public List<String> getCallLog() { return List.copyOf(callLog); }

    public void reset() {
        programmed.clear();
        callCount.set(0);
        callLog.clear();
    }
}
```

### TestSenderConfig

**Location:** `src/test/java/.../support/TestSenderConfig.java`

```java
@TestConfiguration
public class TestSenderConfig {

    @Bean @Primary
    public ProgrammableSender emailSender() { return new ProgrammableSender(Channel.EMAIL); }

    @Bean @Primary
    public ProgrammableSender smsSender() { return new ProgrammableSender(Channel.SMS); }

    @Bean @Primary
    public ProgrammableSender pushSender() { return new ProgrammableSender(Channel.PUSH); }

    // IN_APP sender: use the real InAppSender (it writes to DB, testable)
    // OR create a ProgrammableSender for it too if you want
}
```

Add `@Import(TestSenderConfig.class)` to `BaseIntegrationTest`.

**CRITICAL:** The `ProgrammableSender` beans must have `@Primary` so they override
the `MockEmailSender` etc. But since the mocks are `@Profile("!test")` and tests run
with `@ActiveProfiles("test")`, the mocks won't even load. The `@Primary` is a safety net.

---

## 4. Pure utilities

### TokenBucket

**Location:** `utils/TokenBucket.java`

```java
public class TokenBucket {

    private final double ratePerSec;
    private final int burst;
    private double tokens;
    private Instant lastRefill;
    private final Clock clock;

    public TokenBucket(int ratePerSec, int burst, Clock clock) {
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.tokens = burst;            // start full
        this.lastRefill = clock.instant();
        this.clock = clock;
    }

    /**
     * Try to acquire up to `requested` tokens.
     * Returns the number actually granted (0 to requested). Never blocks.
     */
    public synchronized int tryAcquire(int requested) {
        refill();
        int granted = (int) Math.min(requested, Math.max(0, tokens));
        tokens -= granted;
        return granted;
    }

    /**
     * Return tokens that were acquired but not used
     * (e.g., claimed fewer rows than tokens taken).
     */
    public synchronized void release(int count) {
        tokens = Math.min(burst, tokens + count);
    }

    private void refill() {
        Instant now = clock.instant();
        double elapsedSec = Duration.between(lastRefill, now).toNanos() / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(burst, tokens + elapsedSec * ratePerSec);
            lastRefill = now;
        }
    }

    // For admin limit changes — rebuild bucket with new rates
    public synchronized void updateLimits(int newRatePerSec, int newBurst) {
        // Keep current token fraction proportional
        double fraction = (burst > 0) ? tokens / burst : 1.0;
        // Apply to new burst
        this.tokens = Math.min(newBurst, fraction * newBurst);
        // (Reflection-free: store rate/burst in mutable fields)
        // Since ratePerSec and burst are final, the cleaner approach
        // is to replace the bucket in the registry. See RateLimiterRegistry.refresh().
    }
}
```

### ExponentialBackoffWithJitter

**Location:** `utils/ExponentialBackoffWithJitter.java`

```java
public class ExponentialBackoffWithJitter {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final Random random;

    public ExponentialBackoffWithJitter(long baseDelayMs, long maxDelayMs) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.random = new Random();
    }

    // Overload for deterministic tests
    public ExponentialBackoffWithJitter(long baseDelayMs, long maxDelayMs, Random random) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.random = random;
    }

    /**
     * Calculate the next retry delay for the given attempt number (1-based).
     * Uses equal jitter: delay/2 + random(0, delay/2)
     *
     * @param attemptNo the attempt that just failed (1 = first attempt)
     * @return delay duration before next retry
     */
    public Duration nextDelay(int attemptNo) {
        // Exponential: base * 2^(attempt-1)
        long exponentialMs = (long) (baseDelayMs * Math.pow(2, attemptNo - 1));

        // Cap at max
        long cappedMs = Math.min(exponentialMs, maxDelayMs);

        // Equal jitter: keeps a floor of delay/2, adds random up to delay/2
        long floor = cappedMs / 2;
        long jitter = (long) (random.nextDouble() * floor);

        return Duration.ofMillis(floor + jitter);
    }
}
```

### FailureClassifier

**Location:** `utils/FailureClassifier.java`

```java
public final class FailureClassifier {

    private FailureClassifier() {}

    // Error codes that are PERMANENT — no point retrying
    private static final Set<String> PERMANENT_CODES = Set.of(
        "INVALID_RECIPIENT",
        "INVALID_TOKEN",
        "UNSUBSCRIBED",
        "BAD_PAYLOAD",
        "BLOCKED",
        "CARRIER_REJECTED",
        "SPAM_DETECTED"
    );

    /**
     * Classify a SendResult failure as transient or permanent.
     * Unknown error codes default to TRANSIENT (safer to retry once too many).
     */
    public static boolean isTransient(SendResult result) {
        return switch (result) {
            case SendResult.Success s -> false;          // not a failure
            case SendResult.TransientFailure t -> true;  // always transient
            case SendResult.PermanentFailure p ->
                !PERMANENT_CODES.contains(p.errorCode()); // unknown = treat as transient
        };
    }

    public static boolean isPermanent(SendResult result) {
        return result instanceof SendResult.PermanentFailure p
            && PERMANENT_CODES.contains(p.errorCode());
    }
}
```

---

## 5. RateLimiterRegistry

**Location:** `services/RateLimiterRegistry.java`

```java
@Component
public class RateLimiterRegistry {

    private final ConcurrentHashMap<UUID, TokenBucket> tenantBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, TokenBucket> channelBuckets = new ConcurrentHashMap<>();
    private final TenantRepository tenantRepository;
    private final GlobalChannelLimitRepository globalChannelLimitRepository;
    private final Clock clock;

    // constructor injection

    /**
     * Get or create the tenant's token bucket.
     */
    public TokenBucket tenantBucket(UUID tenantId) {
        return tenantBuckets.computeIfAbsent(tenantId, id -> {
            Tenant tenant = tenantRepository.findById(id).orElseThrow();
            return new TokenBucket(tenant.getRateLimitPerSec(), tenant.getBurst(), clock);
        });
    }

    /**
     * Get or create the global channel bucket.
     */
    public TokenBucket channelBucket(Channel channel) {
        return channelBuckets.computeIfAbsent(channel, ch -> {
            GlobalChannelLimit limit = globalChannelLimitRepository.findById(ch.name())
                .orElse(new GlobalChannelLimit(ch, 500, 1000)); // sensible default
            return new TokenBucket(limit.getRatePerSec(), limit.getBurst(), clock);
        });
    }

    /**
     * Rebuild a tenant's bucket after admin changes their rate limits.
     */
    public void refreshTenant(UUID tenantId) {
        tenantBuckets.remove(tenantId);
    }

    /**
     * Rebuild a channel bucket after admin changes global limits.
     */
    public void refreshChannel(Channel channel) {
        channelBuckets.remove(channel);
    }
}
```

---

## 6. FairTenantSelector

**Location:** `services/FairTenantSelector.java`

```java
@Component
public class FairTenantSelector {

    private final AtomicInteger cursorIndex = new AtomicInteger(0);

    /**
     * Given a list of tenants that have due work, return them in a fair order
     * with a quantum (how many rows to claim) for each.
     *
     * The cursor rotates each call so the same tenant isn't always first.
     */
    public List<TenantWork> selectForTick(List<TenantWithWeight> tenantsWithDueWork,
                                           int baseQuantum) {
        if (tenantsWithDueWork.isEmpty()) return List.of();

        int n = tenantsWithDueWork.size();
        int cursor = cursorIndex.getAndUpdate(i -> (i + 1) % n);

        List<TenantWork> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TenantWithWeight tenant = tenantsWithDueWork.get((cursor + i) % n);
            int quantum = baseQuantum * tenant.weight();
            result.add(new TenantWork(tenant.tenantId(), quantum, tenant.weight()));
        }
        return result;
    }

    public void resetCursor() {
        cursorIndex.set(0);
    }
}

// Supporting records — put in dtos/ package
public record TenantWithWeight(UUID tenantId, int weight) {}
public record TenantWork(UUID tenantId, int quantum, int weight) {}
```

---

## 7. WorkClaimer

**Location:** `services/WorkClaimer.java`

This is the most critical SQL in the entire application.

```java
@Component
public class WorkClaimer {

    private final EntityManager entityManager;
    private final Clock clock;

    private static final String CLAIM_SQL = """
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
            locked_until = :lockUntil,
            attempt_count = attempt_count + 1,
            updated_at = :now
        FROM due
        WHERE n.id = due.id
        RETURNING n.id, n.tenant_id, n.channel, n.recipient,
                  n.subject, n.body, n.attempt_count, n.max_attempts
        """;

    // constructor injection

    /**
     * Claim up to batchSize notification rows for the given tenant and channel.
     * Uses SELECT FOR UPDATE SKIP LOCKED — concurrent callers never get the same row.
     *
     * @return list of claimed notification IDs (may be fewer than batchSize)
     */
    @Transactional
    public List<ClaimedNotification> claim(UUID tenantId, Channel channel, int batchSize,
                                            String workerId, Duration leaseDuration) {
        Instant now = clock.instant();
        Instant lockUntil = now.plus(leaseDuration);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(CLAIM_SQL)
            .setParameter("tenantId", tenantId)
            .setParameter("channel", channel.name())
            .setParameter("now", Timestamp.from(now))
            .setParameter("batchSize", batchSize)
            .setParameter("workerId", workerId)
            .setParameter("lockUntil", Timestamp.from(lockUntil))
            .getResultList();

        // Also insert audit events for each claimed row
        for (Object[] row : rows) {
            UUID notifId = (UUID) row[0];
            insertClaimEvent(notifId, tenantId, now);
        }

        return rows.stream()
            .map(row -> new ClaimedNotification(
                (UUID) row[0],          // id
                (UUID) row[1],          // tenant_id
                Channel.valueOf((String) row[2]),  // channel
                (String) row[3],        // recipient
                (String) row[4],        // subject
                (String) row[5],        // body
                ((Number) row[6]).intValue(),  // attempt_count
                ((Number) row[7]).intValue()   // max_attempts
            ))
            .toList();
    }

    private void insertClaimEvent(UUID notifId, UUID tenantId, Instant now) {
        entityManager.createNativeQuery("""
            INSERT INTO notification_event (id, notification_id, tenant_id, from_status, to_status, reason, actor, occurred_at)
            VALUES (:id, :notifId, :tenantId, 'PENDING', 'PROCESSING', 'claimed', 'DISPATCHER', :now)
            """)
            .setParameter("id", UUID.randomUUID())
            .setParameter("notifId", notifId)
            .setParameter("tenantId", tenantId)
            .setParameter("now", Timestamp.from(now))
            .executeUpdate();
    }

    /**
     * Find distinct tenants that have work due (for FairTenantSelector).
     */
    @Transactional(readOnly = true)
    public List<TenantWithWeight> findTenantsWithDueWork(Instant now) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
            SELECT DISTINCT n.tenant_id, t.weight
            FROM notification n
            JOIN tenant t ON t.id = n.tenant_id
            WHERE n.status IN ('SCHEDULED', 'PENDING', 'RETRYING')
              AND n.next_attempt_at <= :now
              AND t.status = 'ACTIVE'
            """)
            .setParameter("now", Timestamp.from(now))
            .getResultList();

        return rows.stream()
            .map(row -> new TenantWithWeight((UUID) row[0], ((Number) row[1]).intValue()))
            .toList();
    }
}

// Supporting record — put in dtos/
public record ClaimedNotification(
    UUID id,
    UUID tenantId,
    Channel channel,
    String recipient,
    String subject,
    String body,
    int attemptCount,
    int maxAttempts
) {}
```

**IMPORTANT:** The `from_status` in the claim event insertion is simplified to `'PENDING'`.
In reality, the row could have been SCHEDULED, PENDING, or RETRYING before being claimed.
For a more accurate audit trail, the RETURNING clause should also return the previous status.
Update the RETURNING to include the old status if possible, or accept this simplification
and document it.

Better approach — capture the old status via a trigger or by adding it to the CTE:

```sql
-- Updated RETURNING that captures the pre-update status
WITH due AS (
    SELECT id, status as old_status FROM notification
    WHERE tenant_id = :tenantId
      AND channel = :channel
      AND status IN ('SCHEDULED', 'PENDING', 'RETRYING')
      AND next_attempt_at <= :now
    ORDER BY next_attempt_at ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
UPDATE notification n
SET status = 'PROCESSING', ...
FROM due
WHERE n.id = due.id
RETURNING n.*, due.old_status;
```

Use `due.old_status` in the event insertion.

---

## 8. OutcomeRecorder

**Location:** `services/OutcomeRecorder.java`

```java
@Component
public class OutcomeRecorder {

    private final EntityManager entityManager;
    private final ExponentialBackoffWithJitter backoff;
    private final Clock clock;

    // constructor injection
    // Inject backoff from a @Bean in configs/ built from RetryProperties

    /**
     * Record the outcome of a delivery attempt. This is the FENCED WRITE:
     * it only succeeds if the row is still PROCESSING and locked_by matches.
     *
     * If the lease was reaped and re-claimed by another worker, this write
     * silently no-ops (returns false). The provider dedupes via idempotency key.
     */
    @Transactional
    public boolean record(UUID notificationId, UUID tenantId, String workerId,
                           SendResult result, int attemptCount, int maxAttempts) {

        Instant now = clock.instant();

        // 1. Determine new status and fields based on result
        String newStatus;
        String errorCode = null;
        String failureReason = null;
        Instant sentAt = null;
        Instant nextAttemptAt = null;
        String attemptOutcome;
        String providerMsgId = null;

        switch (result) {
            case SendResult.Success s -> {
                newStatus = "SENT";
                sentAt = now;
                attemptOutcome = "SUCCESS";
                providerMsgId = s.providerMessageId();
            }
            case SendResult.TransientFailure t -> {
                errorCode = t.errorCode();
                if (attemptCount >= maxAttempts) {
                    newStatus = "FAILED";
                    failureReason = "RETRIES_EXHAUSTED";
                    attemptOutcome = "TRANSIENT_FAILURE";
                } else {
                    newStatus = "RETRYING";
                    Duration delay = backoff.nextDelay(attemptCount);
                    nextAttemptAt = now.plus(delay);
                    attemptOutcome = "TRANSIENT_FAILURE";
                }
            }
            case SendResult.PermanentFailure p -> {
                newStatus = "FAILED";
                errorCode = p.errorCode();
                failureReason = "PERMANENT_FAILURE: " + p.message();
                attemptOutcome = "PERMANENT_FAILURE";
            }
        }

        // 2. FENCED UPDATE — only succeeds if we still hold the lease
        int updated = entityManager.createNativeQuery("""
            UPDATE notification
            SET status = :newStatus,
                sent_at = :sentAt,
                next_attempt_at = :nextAttemptAt,
                last_error_code = :errorCode,
                failure_reason = :failureReason,
                locked_by = NULL,
                locked_until = NULL,
                updated_at = :now
            WHERE id = :id
              AND status = 'PROCESSING'
              AND locked_by = :workerId
            """)
            .setParameter("newStatus", newStatus)
            .setParameter("sentAt", sentAt != null ? Timestamp.from(sentAt) : null)
            .setParameter("nextAttemptAt", nextAttemptAt != null ? Timestamp.from(nextAttemptAt) : null)
            .setParameter("errorCode", errorCode)
            .setParameter("failureReason", failureReason)
            .setParameter("now", Timestamp.from(now))
            .setParameter("id", notificationId)
            .setParameter("workerId", workerId)
            .executeUpdate();

        if (updated == 0) {
            // Lease was reaped — our write is stale. Log and discard.
            // Provider deduplicates via idempotency key, so no duplicate delivery.
            return false;
        }

        // 3. Record delivery attempt
        entityManager.createNativeQuery("""
            INSERT INTO delivery_attempt
                (id, notification_id, attempt_no, started_at, finished_at,
                 outcome, error_code, error_message, provider_message_id, latency_ms)
            VALUES (:id, :notifId, :attemptNo, :startedAt, :finishedAt,
                    :outcome, :errorCode, :errorMsg, :providerMsgId, :latencyMs)
            """)
            .setParameter("id", UUID.randomUUID())
            .setParameter("notifId", notificationId)
            .setParameter("attemptNo", attemptCount)
            .setParameter("startedAt", Timestamp.from(now))  // approximate
            .setParameter("finishedAt", Timestamp.from(now))
            .setParameter("outcome", attemptOutcome)
            .setParameter("errorCode", errorCode)
            .setParameter("errorMsg", result instanceof SendResult.TransientFailure t ? t.message()
                : result instanceof SendResult.PermanentFailure p ? p.message() : null)
            .setParameter("providerMsgId", providerMsgId)
            .setParameter("latencyMs", 0L) // TODO: track actual latency
            .executeUpdate();

        // 4. Audit event
        String fromStatus = "PROCESSING";
        String reason = switch (result) {
            case SendResult.Success s -> "provider_success";
            case SendResult.TransientFailure t ->
                (attemptCount >= maxAttempts) ? "retries_exhausted" : "transient_failure:" + t.errorCode();
            case SendResult.PermanentFailure p -> "permanent_failure:" + p.errorCode();
        };

        entityManager.createNativeQuery("""
            INSERT INTO notification_event
                (id, notification_id, tenant_id, from_status, to_status, reason, actor, occurred_at)
            VALUES (:id, :notifId, :tenantId, :fromStatus, :toStatus, :reason, 'DISPATCHER', :now)
            """)
            .setParameter("id", UUID.randomUUID())
            .setParameter("notifId", notificationId)
            .setParameter("tenantId", tenantId)
            .setParameter("fromStatus", fromStatus)
            .setParameter("toStatus", newStatus)
            .setParameter("reason", reason)
            .setParameter("now", Timestamp.from(now))
            .executeUpdate();

        return true;
    }
}
```

---

## 9. DeliveryWorker

**Location:** `services/DeliveryWorker.java`

```java
public class DeliveryWorker implements Runnable {

    private final ClaimedNotification notification;
    private final ChannelSender sender;
    private final OutcomeRecorder outcomeRecorder;
    private final String workerId;

    // constructor — all args

    @Override
    public void run() {
        OutboundMessage msg = new OutboundMessage(
            notification.id(),
            notification.id().toString(),    // idempotency key = notification ID
            notification.recipient(),
            notification.subject(),
            notification.body(),
            notification.channel(),
            ""                                // tenant slug (optional, for logging)
        );

        // Call the provider — NO database transaction held during this call
        SendResult result = sender.send(msg);

        // Record the outcome — fenced write inside a transaction
        outcomeRecorder.record(
            notification.id(),
            notification.tenantId(),
            workerId,
            result,
            notification.attemptCount(),
            notification.maxAttempts()
        );
    }
}
```

---

## 10. LeaseReaper

**Location:** `services/LeaseReaper.java`

```java
@Component
public class LeaseReaper {

    private final EntityManager entityManager;
    private final Clock clock;

    // constructor injection

    /**
     * Find PROCESSING notifications whose lease has expired (worker hung or crashed).
     * Transition them to RETRYING with an ABANDONED attempt so they get re-claimed.
     *
     * Called periodically by DispatchScheduler.
     */
    @Transactional
    public int reap() {
        Instant now = clock.instant();

        // Find expired leases
        @SuppressWarnings("unchecked")
        List<Object[]> expired = entityManager.createNativeQuery("""
            SELECT id, tenant_id, attempt_count, max_attempts, locked_by
            FROM notification
            WHERE status = 'PROCESSING'
              AND locked_until < :now
            FOR UPDATE SKIP LOCKED
            """)
            .setParameter("now", Timestamp.from(now))
            .getResultList();

        int reaped = 0;
        for (Object[] row : expired) {
            UUID id = (UUID) row[0];
            UUID tenantId = (UUID) row[1];
            int attemptCount = ((Number) row[2]).intValue();
            int maxAttempts = ((Number) row[3]).intValue();
            String oldWorkerId = (String) row[4];

            // Decide: RETRYING (if attempts left) or FAILED (exhausted)
            String newStatus = (attemptCount >= maxAttempts) ? "FAILED" : "RETRYING";
            String reason = (attemptCount >= maxAttempts)
                ? "lease_expired_retries_exhausted" : "lease_expired";

            // Transition the row
            entityManager.createNativeQuery("""
                UPDATE notification
                SET status = :newStatus,
                    locked_by = NULL,
                    locked_until = NULL,
                    next_attempt_at = :nextAt,
                    failure_reason = CASE WHEN :newStatus = 'FAILED' THEN 'LEASE_EXPIRED' ELSE failure_reason END,
                    updated_at = :now
                WHERE id = :id AND status = 'PROCESSING'
                """)
                .setParameter("newStatus", newStatus)
                .setParameter("nextAt", Timestamp.from(now))  // immediate re-claim for RETRYING
                .setParameter("now", Timestamp.from(now))
                .setParameter("id", id)
                .executeUpdate();

            // Record ABANDONED attempt
            entityManager.createNativeQuery("""
                INSERT INTO delivery_attempt
                    (id, notification_id, attempt_no, started_at, finished_at,
                     outcome, error_code, error_message, latency_ms)
                VALUES (:id, :notifId, :attemptNo, :now, :now,
                        'ABANDONED', 'LEASE_EXPIRED', :msg, 0)
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("notifId", id)
                .setParameter("attemptNo", attemptCount)
                .setParameter("now", Timestamp.from(now))
                .setParameter("msg", "Worker " + oldWorkerId + " lease expired")
                .executeUpdate();

            // Audit event
            entityManager.createNativeQuery("""
                INSERT INTO notification_event
                    (id, notification_id, tenant_id, from_status, to_status, reason, actor, occurred_at)
                VALUES (:id, :notifId, :tenantId, 'PROCESSING', :toStatus, :reason, 'REAPER', :now)
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("notifId", id)
                .setParameter("tenantId", tenantId)
                .setParameter("toStatus", newStatus)
                .setParameter("reason", reason)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();

            reaped++;
        }

        return reaped;
    }
}
```

---

## 11. DispatchScheduler — the orchestrator

**Location:** `services/DispatchScheduler.java`

```java
@Component
public class DispatchScheduler {

    private final WorkClaimer workClaimer;
    private final FairTenantSelector fairTenantSelector;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final ChannelSenderRegistry senderRegistry;
    private final OutcomeRecorder outcomeRecorder;
    private final LeaseReaper leaseReaper;
    private final DispatcherProperties props;
    private final Clock clock;

    // Per-channel thread pools — created in a @Configuration class or here
    private final Map<Channel, ThreadPoolExecutor> pools;

    private final String workerId = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    // constructor injection

    @PostConstruct
    public void init() {
        // Build per-channel thread pools from config
        // (This could also be in a separate ChannelWorkerPools @Configuration class)

        if (props.isAutoStart()) {
            start();
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dispatch-scheduler");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::safeTick,
                0, props.getTickIntervalMs(), TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false) && scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * Called by the scheduler loop OR manually in tests.
     * Public so tests can call tick() directly for deterministic control.
     */
    public void tick() {
        Instant now = clock.instant();

        // 1. Run the lease reaper periodically
        //    (In production, this runs on its own schedule. For simplicity,
        //     we run it every tick but the reaper is fast when there's nothing to reap.)
        leaseReaper.reap();

        // 2. Find tenants with due work
        List<TenantWithWeight> tenantsWithWork = workClaimer.findTenantsWithDueWork(now);
        if (tenantsWithWork.isEmpty()) return;

        // 3. Fair selection: order tenants and assign quantum
        List<TenantWork> workPlan = fairTenantSelector.selectForTick(
            tenantsWithWork, props.getBaseQuantum());

        // 4. For each tenant, for each channel, claim and dispatch
        for (TenantWork tw : workPlan) {
            for (Channel channel : Channel.values()) {
                dispatchForTenantChannel(tw, channel, now);
            }
        }
    }

    private void dispatchForTenantChannel(TenantWork tw, Channel channel, Instant now) {
        ThreadPoolExecutor pool = pools.get(channel);
        if (pool == null) return;

        // How many can the pool accept?
        int poolFreeSlots = pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity()
            - pool.getActiveCount() - pool.getQueue().size();
        if (poolFreeSlots <= 0) return;

        int wanted = Math.min(tw.quantum(), poolFreeSlots);

        // Rate limit check: tenant bucket
        TokenBucket tenantBucket = rateLimiterRegistry.tenantBucket(tw.tenantId());
        int tenantGranted = tenantBucket.tryAcquire(wanted);
        if (tenantGranted == 0) return;

        // Rate limit check: global channel bucket
        TokenBucket channelBucket = rateLimiterRegistry.channelBucket(channel);
        int channelGranted = channelBucket.tryAcquire(tenantGranted);
        if (channelGranted == 0) {
            tenantBucket.release(tenantGranted);  // return unused tenant tokens
            return;
        }

        // If channel gave fewer than tenant, return the difference to tenant
        if (channelGranted < tenantGranted) {
            tenantBucket.release(tenantGranted - channelGranted);
        }

        // Claim rows
        Duration leaseDuration = Duration.ofSeconds(props.getLeaseDurationSeconds());
        List<ClaimedNotification> claimed = workClaimer.claim(
            tw.tenantId(), channel, channelGranted, workerId, leaseDuration);

        // Return unused tokens if we claimed fewer than granted
        if (claimed.size() < channelGranted) {
            int unused = channelGranted - claimed.size();
            tenantBucket.release(unused);
            channelBucket.release(unused);
        }

        // Submit to pool
        ChannelSender sender = senderRegistry.get(channel);
        for (ClaimedNotification notif : claimed) {
            DeliveryWorker worker = new DeliveryWorker(notif, sender, outcomeRecorder, workerId);
            try {
                pool.submit(worker);
            } catch (RejectedExecutionException e) {
                // Pool full — release this row back to PENDING
                // No attempt consumed (the claim already incremented attempt_count,
                // so we need to decrement it back)
                releaseBack(notif.id(), now);
            }
        }
    }

    /**
     * Release a claimed row back to PENDING if the pool rejected it.
     * Decrements attempt_count since the attempt was not actually made.
     */
    private void releaseBack(UUID notificationId, Instant now) {
        // This needs its own transaction — use programmatic tx or a helper service
        // For simplicity, use a native query
        // Note: this should ideally be in a separate @Transactional method
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            // Log but don't crash the scheduler thread
            // logger.error("Dispatch tick failed", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
        pools.values().forEach(ThreadPoolExecutor::shutdown);
    }
}
```

### Pool construction (in configs/ or inside DispatchScheduler)

**Location:** `configs/WorkerPoolConfig.java`

```java
@Configuration
public class WorkerPoolConfig {

    @Bean
    public Map<Channel, ThreadPoolExecutor> channelWorkerPools(PoolProperties poolProps) {
        Map<Channel, ThreadPoolExecutor> pools = new EnumMap<>(Channel.class);

        pools.put(Channel.EMAIL, buildPool(poolProps.getEmail(), "email-worker-"));
        pools.put(Channel.SMS, buildPool(poolProps.getSms(), "sms-worker-"));
        pools.put(Channel.PUSH, buildPool(poolProps.getPush(), "push-worker-"));
        pools.put(Channel.IN_APP, buildPool(poolProps.getInApp(), "inapp-worker-"));

        return pools;
    }

    private ThreadPoolExecutor buildPool(PoolProperties.PoolConfig config, String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return new ThreadPoolExecutor(
            config.getCoreSize(),
            config.getMaxSize(),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(config.getQueueCapacity()),
            r -> {
                Thread t = new Thread(r, prefix + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()   // throws RejectedExecutionException
        );
    }
}
```

### Backoff bean

**Location:** `configs/RetryConfig.java`

```java
@Configuration
public class RetryConfig {

    @Bean
    public ExponentialBackoffWithJitter backoffPolicy(RetryProperties retryProps) {
        return new ExponentialBackoffWithJitter(
            retryProps.getBaseDelayMs(),
            retryProps.getMaxDelayMs()
        );
    }
}
```

---

## 12. Unit tests

### TokenBucketTest

**Location:** `src/test/java/.../unit/TokenBucketTest.java`

```java
class TokenBucketTest {

    @Test void initiallyFull_grantsBurstAmount() {
        // rate=10, burst=20 → tryAcquire(20) = 20
    }

    @Test void emptyBucket_grantsZero() {
        // drain → tryAcquire(1) = 0
    }

    @Test void refillsOverTime() {
        // drain → advance 1s → tryAcquire(15) = 10 (rate is 10/s)
    }

    @Test void cappedAtBurst() {
        // drain → advance 10s → tryAcquire(100) = 20 (burst cap)
    }

    @Test void partialGrant() {
        // drain → advance 500ms → tryAcquire(10) = 5 (half-second refill)
    }

    @Test void releaseReturnsTokens() {
        // take 10 → release 5 → tryAcquire(6) = 5+refill
    }

    @Test void releaseCappedAtBurst() {
        // full → release(10) → tokens still = burst, not burst+10
    }
}
```

### ExponentialBackoffWithJitterTest

**Location:** `src/test/java/.../unit/ExponentialBackoffWithJitterTest.java`

```java
class ExponentialBackoffWithJitterTest {

    @Test void firstAttempt_delayAroundBase() {
        // attempt 1, base=2s → delay ∈ [1s, 2s]
    }

    @Test void exponentialGrowth() {
        // attempt 1,2,3 → raw delays 2s, 4s, 8s
    }

    @Test void cappedAtMaxDelay() {
        // attempt 20, base=2s, cap=5m → delay ∈ [2.5m, 5m], not 2^20 seconds
    }

    @Test void jitterBounds() {
        // Run 1000 times → every delay in [floor, cap]
        // floor = rawDelay/2, cap = rawDelay
    }

    @Test void deterministicWithSeededRandom() {
        // Same seed → same delays
    }
}
```

### FailureClassifierTest

**Location:** `src/test/java/.../unit/FailureClassifierTest.java`

```java
class FailureClassifierTest {

    @Test void success_isNotTransient()       { assertFalse(isTransient(new Success("id"))); }
    @Test void transient_isTransient()        { assertTrue(isTransient(new TransientFailure("TIMEOUT", ""))); }
    @Test void permanent_known_isPermanent()  { assertTrue(isPermanent(new PermanentFailure("INVALID_RECIPIENT", ""))); }
    @Test void permanent_unknown_isTransient(){ assertTrue(isTransient(new PermanentFailure("UNKNOWN_CODE", ""))); }
}
```

### FairTenantSelectorTest

**Location:** `src/test/java/.../unit/FairTenantSelectorTest.java`

```java
class FairTenantSelectorTest {

    @Test void emptyList_returnsEmpty() {}

    @Test void singleTenant_getsFullQuantum() {
        // tenant A weight=1, base=20 → quantum=20
    }

    @Test void weightMultipliesQuantum() {
        // tenant A weight=3, base=20 → quantum=60
    }

    @Test void cursorRotates() {
        // 3 tenants [A,B,C] → tick1 starts at A, tick2 starts at B, tick3 starts at C
    }

    @Test void skipsEmptyTenantGracefully() {
        // If tenant list changes between ticks, cursor wraps correctly
    }
}
```

---

## 13. Integration tests

### DispatcherHappyPathTest

**Location:** `src/test/java/.../integration/DispatcherHappyPathTest.java`

```java
class DispatcherHappyPathTest extends BaseIntegrationTest {

    @Autowired DispatchScheduler scheduler;
    @Autowired ProgrammableSender emailSender;   // injected by TestSenderConfig
    @Autowired NotificationRepository notificationRepo;
    @Autowired DeliveryAttemptRepository attemptRepo;
    @Autowired NotificationEventRepository eventRepo;

    @Test void submit_then_tick_sends_notification() {
        // 1. Submit a notification via API → PENDING
        UUID id = submitNotification("test@example.com", "welcome",
            Map.of("name", "Alice", "companyName", "Acme"));

        // 2. Tick the dispatcher
        scheduler.tick();

        // 3. Wait for async worker to finish
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Notification n = notificationRepo.findById(id).orElseThrow();
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        });

        // 4. Verify delivery attempt
        List<DeliveryAttempt> attempts = attemptRepo.findByNotificationIdOrderByAttemptNoAsc(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);

        // 5. Verify audit trail
        List<NotificationEvent> events = eventRepo.findByNotificationIdOrderByOccurredAtAsc(id);
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        // null→PENDING, PENDING→PROCESSING, PROCESSING→SENT

        // 6. Verify sender was called exactly once
        assertThat(emailSender.getCallCount()).isEqualTo(1);
    }

    @Test void multiple_notifications_all_sent() {
        // Submit 10 notifications → tick → all SENT
    }

    @Test void scheduled_notification_not_sent_before_time() {
        // Submit with scheduledAt = now + 10min → tick → still SCHEDULED
        // Advance clock past scheduledAt → tick → SENT
    }
}
```

### ConcurrentClaimTest

**Location:** `src/test/java/.../integration/ConcurrentClaimTest.java`

```java
class ConcurrentClaimTest extends BaseIntegrationTest {

    @Test void one_thousand_rows_claimed_exactly_once_by_concurrent_workers() {
        // 1. Insert 1000 PENDING notifications directly into DB
        // 2. Run 8 concurrent threads, each calling workClaimer.claim()
        // 3. Collect all claimed IDs
        // 4. Assert: total claimed = 1000, no duplicates
        // 5. Assert: every notification is PROCESSING
    }
}
```

### RetryFlowTest

**Location:** `src/test/java/.../integration/RetryFlowTest.java`

```java
class RetryFlowTest extends BaseIntegrationTest {

    @Test void transient_failure_retries_then_succeeds() {
        // 1. Program sender: fail twice (TIMEOUT), then succeed
        // 2. Submit notification
        // 3. Tick → RETRYING (attempt 1 failed)
        // 4. Advance clock past backoff delay
        // 5. Tick → RETRYING (attempt 2 failed)
        // 6. Advance clock past backoff delay
        // 7. Tick → SENT (attempt 3 succeeded)
        // 8. Verify: 3 delivery attempts, status=SENT
    }

    @Test void retries_exhausted_becomes_failed() {
        // 1. Program sender: always TransientFailure
        // 2. maxAttempts = 3
        // 3. Tick 3 times with clock advances between
        // 4. Verify: status=FAILED, reason=RETRIES_EXHAUSTED, 3 attempts
    }

    @Test void permanent_failure_no_retry() {
        // 1. Program sender: PermanentFailure("INVALID_RECIPIENT")
        // 2. Tick once → FAILED immediately
        // 3. Verify: 1 attempt, no retry
    }

    @Test void backoff_spacing_follows_exponential_policy() {
        // 1. Program sender: fail 3 times then succeed
        // 2. After each tick, check next_attempt_at
        // 3. Gaps should follow exponential pattern (within jitter bounds)
    }
}
```

### LeaseRecoveryTest

**Location:** `src/test/java/.../integration/LeaseRecoveryTest.java`

```java
class LeaseRecoveryTest extends BaseIntegrationTest {

    @Test void expired_lease_is_reaped_and_requeued() {
        // 1. Submit notification → tick → PROCESSING
        // 2. Use a sender that BLOCKS forever (or very long)
        //    OR: manually set locked_until to past via direct DB update
        // 3. Advance clock past lease duration
        // 4. Call leaseReaper.reap()
        // 5. Verify: status = RETRYING, ABANDONED attempt logged
        // 6. Tick again → claimed and sent
    }

    @Test void stale_worker_write_is_rejected_after_reap() {
        // This tests the FENCING condition.
        // 1. Submit notification
        // 2. Manually set notification to PROCESSING with locked_by = "stale-worker"
        //    and locked_until = past
        // 3. Reaper runs → RETRYING
        // 4. Call outcomeRecorder.record() with workerId = "stale-worker"
        // 5. Verify: returns false (fenced out), status is still RETRYING (not overwritten)
    }
}
```

### RateLimitTest

**Location:** `src/test/java/.../integration/RateLimitTest.java`

```java
class RateLimitTest extends BaseIntegrationTest {

    @Test void tenant_rate_limit_is_respected() {
        // 1. Set tenant rate = 10/s, burst = 10
        // 2. Submit 100 notifications
        // 3. Tick → at most 10 claimed (burst)
        // 4. Advance clock 1 second → tick → at most 10 more
        // 5. Total after 3 ticks over 2 seconds ≤ 10 + 10 + 10
    }

    @Test void channel_rate_limit_caps_across_tenants() {
        // 1. Set global EMAIL rate = 5/s
        // 2. Two tenants, each with 50 queued
        // 3. Tick → total EMAIL sent ≤ 5 (channel cap)
    }
}
```

### FairnessTest

**Location:** `src/test/java/.../integration/FairnessTest.java`

```java
class FairnessTest extends BaseIntegrationTest {

    @Test void small_tenant_finishes_before_large_tenant_is_half_done() {
        // 1. Tenant A: 2000 queued notifications, weight=1
        // 2. Tenant B: 20 queued notifications, weight=1
        // 3. Run enough ticks to process ~100 per tenant
        // 4. Verify: ALL of B's 20 are SENT
        // 5. Verify: A has processed < 200 (B wasn't starved)
    }

    @Test void weighted_tenant_gets_proportional_throughput() {
        // 1. Tenant A: weight=1, 100 queued
        // 2. Tenant B: weight=3, 100 queued
        // 3. Run ticks
        // 4. Verify: B processed ~3× as many as A
    }
}
```

### PoolBackPressureTest

**Location:** `src/test/java/.../integration/PoolBackPressureTest.java`

```java
class PoolBackPressureTest extends BaseIntegrationTest {

    @Test void pool_rejection_releases_row_back_to_pending() {
        // 1. Use test profile with tiny pool: core=1, max=1, queue=1
        // 2. Use a sender with 5-second latency (blocks the single thread)
        // 3. Submit 10 notifications → tick
        // 4. Pool accepts 2 (1 running + 1 queued), rejects 8
        // 5. Verify: rejected rows are back in PENDING, not lost
        // 6. Verify: no attempt consumed for rejected rows
    }
}
```

---

## 14. Checklist before commit

- [ ] `./gradlew test` passes — all unit + integration tests green
- [ ] Happy path: submit → tick → SENT with 1 attempt and 3 audit events
- [ ] Concurrent claim: 1000 rows, 8 claimers → each row exactly once
- [ ] Transient → retry → succeed: correct attempt count, backoff spacing
- [ ] Retries exhausted → FAILED with correct reason
- [ ] Permanent failure → FAILED immediately, 1 attempt
- [ ] Lease expiry: reaper recovers stuck rows, ABANDONED attempt logged
- [ ] Fencing: stale worker write rejected (returns false)
- [ ] Token bucket: tenant rate limit respected
- [ ] Channel rate limit: caps across all tenants
- [ ] Fairness: small tenant finishes first when large tenant has huge backlog
- [ ] Pool back-pressure: rejected rows released to PENDING, no data loss
- [ ] Scheduled notification: not dispatched before time, dispatched after
- [ ] Mock providers: configurable latency/failure in dev, ProgrammableSender in test
- [ ] No `Instant.now()` — all use injected Clock
- [ ] No `Thread.sleep` in tests — MutableClock + Awaitility only
- [ ] All classes in correct flat package

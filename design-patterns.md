# Design Patterns & Engineering Strategies

> Every pattern here solves a specific problem in the notification service.
> None are used for ceremony — each one earns its place by preventing a concrete failure mode
> or enabling a concrete capability.

---

## 1. Concurrency & Data Integrity Patterns

### 1.1 Queue-in-DB with SKIP LOCKED

**Problem:** We need a work queue where multiple worker threads can grab notifications to send
concurrently, without any two workers ever processing the same notification.

**Why not Kafka/Redis?** The brief explicitly rules out distributed infrastructure. Adding a
separate queue means two sources of truth (queue + DB) that can drift apart — notifications
can get lost between them, or get processed but not marked as such.

**How it works:** The `notification` table doubles as the queue. A single SQL statement atomically
selects eligible rows, locks them, and transitions them to PROCESSING:

```sql
WITH due AS (
    SELECT id FROM notification
    WHERE tenant_id = ? AND channel = ? AND status IN ('PENDING','RETRYING')
      AND next_attempt_at <= now()
    ORDER BY next_attempt_at
    LIMIT ?
    FOR UPDATE SKIP LOCKED       -- the magic: skip rows another worker already locked
)
UPDATE notification SET status = 'PROCESSING', locked_by = ?, locked_until = ?
FROM due WHERE notification.id = due.id
RETURNING *;
```

**What it prevents:**
- Double-processing: two workers can never claim the same row
- Deadlocks: `SKIP LOCKED` never waits — workers always make progress
- Lost messages: the row stays in the DB whether it's queued, in-flight, or terminal
- Split-brain: one table, one transaction, one source of truth

**Trade-off:** PostgreSQL row locks are held for the duration of the claiming transaction only
(microseconds). The "lease" (`locked_by` + `locked_until`) is an application-level construct
checked on outcome writes — not a DB lock held during the provider call.

---

### 1.2 Lease-based claiming with fenced writes

**Problem:** A worker claims a notification, calls the provider, but hangs (GC pause, slow
provider, network partition). The row is stuck in PROCESSING forever.

**Solution — two mechanisms working together:**

**Lease:** Every claim sets `locked_by = workerId` and `locked_until = now + 60s`. The
`LeaseReaper` runs every 30s and finds rows where `status = 'PROCESSING' AND locked_until < now()`.
It transitions them to RETRYING with an ABANDONED attempt, making them claimable again.

**Fencing:** When the original (stale) worker finally returns and tries to write its result,
the outcome UPDATE includes a fencing condition:

```sql
UPDATE notification
SET status = ?, ...
WHERE id = ? AND status = 'PROCESSING' AND locked_by = ?
```

If the reaper already moved the row and another worker re-claimed it, `locked_by` no longer
matches. The UPDATE affects 0 rows. The stale worker detects this (RETURNING is empty) and
discards its result.

**What it prevents:**
- Stuck rows: the reaper recovers them within `reaper-interval`
- Stale overwrites: the fencing condition makes late writes a no-op
- Double sends: the provider receives the `notification.id` as idempotency key, so even if
  two workers both call the provider for the same notification, the provider deduplicates

**Why this over optimistic locking (@Version)?** Optimistic locking requires a read-then-write
cycle and throws on conflict. The fenced write is a single conditional UPDATE that silently
succeeds or silently no-ops — no exceptions, no retries, no read step.

---

### 1.3 Idempotent ingestion (unique constraint + request hash)

**Problem:** The tenant's backend might retry a notification submit (network timeout, load
balancer retry, client bug). We must never create two notification rows for the same logical
message.

**Solution — three layers:**

1. **Unique constraint:** `UNIQUE(tenant_id, idempotency_key)` on the notification table.
   If two requests race with the same key, one wins the INSERT and the other hits the
   constraint violation.

2. **Request hash:** `SHA-256(canonical(channel + recipient + templateCode + sortedVariables))`.
   Stored alongside the idempotency key. Same key + same hash = "this is a retry of the same
   request" → return the existing notification (200 OK). Same key + different hash = "this is
   a different request reusing a key" → reject with 422.

3. **Race handling:** The loser of the constraint race catches `DataIntegrityViolationException`,
   fetches the winner's row, compares hashes, and returns 200 or 422 accordingly. No retry
   loop, no distributed lock.

**What it prevents:**
- Duplicate notifications: structurally impossible at the DB level
- Silent payload changes: the hash detects "same key, different content"
- Race conditions: the constraint serializes concurrent inserts; the loser always converges
  to the same answer as the winner

---

### 1.4 Optimistic locking on Tenant

**Problem:** Two platform admins concurrently updating a tenant's rate limits could overwrite
each other's changes (last-write-wins).

**Solution:** JPA `@Version` on the Tenant entity. Hibernate adds `WHERE version = ?` to every
UPDATE. If the version changed between the read and the write, it throws
`OptimisticLockingFailureException` → the API returns 409 Conflict.

**Why here but not on Notification?** Notifications are updated by workers in a hot loop where
conflicts are expected and must be silent (fenced writes). Tenants are updated by admins
rarely, where conflicts should be loud (409) so the admin retries with fresh data.

---

## 2. Reliability & Fault Tolerance Patterns

### 2.1 Retry with exponential backoff and jitter

**Problem:** A transient provider failure (timeout, 503, rate limit) will likely succeed if
retried later. But retrying immediately (or at fixed intervals) creates a thundering herd
that hammers the already-struggling provider.

**Solution:**

```
delay = min(cap, base × 2^(attempt - 1))
jittered = delay/2 + random(0, delay/2)      ← "equal jitter"
```

- `base = 2s`: first retry after ~1-2s
- `cap = 5m`: never wait more than 5 minutes
- Equal jitter (not full jitter): keeps a floor of `delay/2` so retries don't collapse to
  near-zero, while still spreading them enough to avoid thundering herd

**Concrete example:** max_attempts = 5, base = 2s, cap = 5m

| Attempt | Raw delay | Jitter range | Typical wait |
|---------|-----------|-------------|-------------|
| 1 | 2s | 1s – 2s | ~1.5s |
| 2 | 4s | 2s – 4s | ~3s |
| 3 | 8s | 4s – 8s | ~6s |
| 4 | 16s | 8s – 16s | ~12s |
| 5 | 32s | 16s – 32s | ~24s |

**Why equal jitter over full jitter?** Full jitter (`random(0, delay)`) can produce near-zero
delays on early attempts, which isn't useful back-pressure. Equal jitter guarantees at least
`delay/2`, which gives the provider meaningful recovery time.

**What it prevents:**
- Thundering herd: jitter desynchronizes retries across notifications
- Provider overload: exponential growth gives progressively more breathing room
- Infinite retries: `max_attempts` caps the total budget; after that, the notification is FAILED

---

### 2.2 Failure classification (transient vs permanent)

**Problem:** Not all failures should be retried. Retrying "invalid email address" wastes
attempts and delays the FAILED terminal state the tenant needs to see.

**Solution:** `FailureClassifier` maps provider error codes to two categories:

| Category | Error codes | Action |
|----------|------------|--------|
| Transient | TIMEOUT, CONNECTION_ERROR, RATE_LIMITED, PROVIDER_5XX | → RETRYING (if attempts left) or FAILED |
| Permanent | INVALID_RECIPIENT, UNSUBSCRIBED, BAD_PAYLOAD, BLOCKED | → FAILED immediately (1 attempt) |

**Implementation:** A simple `Map<String, FailureType>` lookup. Unknown error codes default to
transient (safer to retry once too many than to permanently fail a sendable notification).

**What it prevents:**
- Wasted retries on unsendable messages
- Delayed failure signals (tenant sees FAILED immediately for bad addresses)
- Silent swallowing of unknown errors (default to transient = at least try again)

---

### 2.3 At-least-once delivery, effectively-once receipt

**Problem:** True exactly-once delivery across a network boundary is impossible. The provider
might receive and process our request but the response gets lost — we can't know if it was
delivered without calling again, which might deliver it twice.

**Solution — layered idempotency:**

```
Layer 1: Ingestion        UNIQUE(tenant_id, idempotency_key)     → one notification row
Layer 2: Claim            SKIP LOCKED + status predicate          → one worker per row
Layer 3: Outcome write    fenced by locked_by                     → one result per attempt
Layer 4: Provider call    notification.id as idempotency key      → provider deduplicates
Layer 5: InAppSender      UNIQUE(notification_id) on in_app_msg  → one inbox entry per notification
```

Each layer catches a different class of duplicate. Together they give us "at-least-once to the
provider, effectively-once to the recipient."

---

### 2.4 Rate-limited work is deferred, not failed

**Problem:** When a tenant hits their rate limit, their notifications should wait and be sent
later — not fail permanently. Rate limiting is back-pressure, not an error.

**Solution:** The token bucket check happens at claim time, before any rows are locked.
If `tryAcquire()` returns 0, the scheduler simply skips that tenant×channel for this tick.
The notifications stay in PENDING/RETRYING with their `next_attempt_at` unchanged, and will
be picked up on the next tick when tokens have refilled.

**What it prevents:**
- Treating rate limits as failures (which would consume retry attempts)
- Claiming rows we can't send (which would waste lease slots and pool threads)
- User-visible errors for a system-level control mechanism

---

## 3. Structural Design Patterns

### 3.1 Strategy pattern — ChannelSender

**Problem:** Four channels (EMAIL, SMS, PUSH, IN_APP) each have completely different send
logic, provider APIs, recipient formats, and failure modes. But the dispatcher shouldn't
know or care about these differences.

**Solution:** `ChannelSender` interface with four implementations:

```java
public interface ChannelSender {
    Channel channel();
    SendResult send(OutboundMessage msg);
}

// Implementations:
MockEmailSender, MockSmsSender, MockPushSender, InAppSender
```

The dispatcher calls `channelSenderRegistry.get(channel).send(msg)` — uniform for every channel.
Adding WhatsApp or Slack later means writing one new class and registering it. Zero changes
to the dispatcher, worker, or outcome recorder.

**Why Strategy over polymorphism on the Notification entity?** The notification is a data object
(JPA entity). Putting send logic on it would mix persistence concerns with I/O concerns and
make testing impossible without a database.

---

### 3.2 Sealed result type — SendResult

**Problem:** Provider calls can succeed, fail transiently, or fail permanently. Using exceptions
for control flow (try/catch for transient vs permanent) is fragile — a missing catch block
means a transient failure crashes the worker instead of retrying.

**Solution:**

```java
public sealed interface SendResult {
    record Success(String providerMessageId)             implements SendResult {}
    record Transient(String errorCode, String message)   implements SendResult {}
    record Permanent(String errorCode, String message)   implements SendResult {}
}
```

The compiler enforces exhaustive handling:

```java
switch (result) {
    case Success s   -> recordSuccess(notification, s);
    case Transient t -> scheduleRetry(notification, t);
    case Permanent p -> recordFailure(notification, p);
}
```

**What it prevents:**
- Unhandled failure cases: the compiler tells you if you missed one
- Exception-driven control flow: no try/catch pyramid, no accidental swallowing
- Stringly-typed errors: each variant carries typed fields, not a generic message

---

### 3.3 Registry pattern — ChannelSenderRegistry, RateLimiterRegistry

**Problem:** Multiple components need to look up the right `ChannelSender` or `TokenBucket` by
channel or tenant ID. Passing four sender beans and N bucket instances through constructors
is unwieldy and doesn't handle dynamic additions (new tenants created at runtime).

**Solution:**

```java
@Component
public class ChannelSenderRegistry {
    private final Map<Channel, ChannelSender> senders;

    public ChannelSenderRegistry(List<ChannelSender> allSenders) {
        this.senders = allSenders.stream()
            .collect(toMap(ChannelSender::channel, identity()));
    }

    public ChannelSender get(Channel ch) {
        return Optional.ofNullable(senders.get(ch))
            .orElseThrow(() -> new IllegalArgumentException("No sender for " + ch));
    }
}
```

Spring auto-discovers all `ChannelSender` beans and injects them as a list. The registry
indexes them by channel. Adding a new channel = adding a new `@Component` — the registry
picks it up automatically.

`RateLimiterRegistry` does the same for token buckets, keyed by tenant ID, with a `refresh()`
method for when an admin changes rate limits.

---

### 3.4 State machine pattern — NotificationStateMachine

**Problem:** Notifications have 7 states and 12 legal transitions. Without a central guard,
it's easy to write code that transitions SENT → PROCESSING (nonsensical) or CANCELLED → RETRYING
(dangerous). Bugs like these are hard to catch in review and catastrophic in production.

**Solution:** All transitions go through a single static method:

```java
public static void transition(Notification n, NotificationStatus to,
                               String reason, String actor, Clock clock) {
    NotificationStatus from = n.getStatus();
    if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
        throw new IllegalStateException("Illegal transition: " + from + " → " + to);
    }
    n.setStatus(to);
    n.setUpdatedAt(clock.instant());
}
```

**Rules enforced:**
- Every transition is whitelisted — unlisted transitions throw immediately
- Every transition is audited — the caller must persist a `NotificationEvent` in the same tx
- No direct `setStatus()` calls outside the state machine — enforced by code review convention
  (the setter could be package-private for stronger enforcement)

**What it prevents:**
- Impossible state transitions (SENT → PROCESSING)
- Unaudited transitions (every change has a reason and actor)
- State machine drift (one place to read all transitions, not scattered across 10 services)

---

### 3.5 Template method pattern — DeliveryWorker

**Problem:** Every delivery follows the same sequence: load notification → call provider →
classify result → record outcome → write audit event. But the "call provider" step varies
by channel.

**Solution:** `DeliveryWorker.run()` defines the skeleton:

```java
public void run() {
    // 1. Record attempt start
    DeliveryAttempt attempt = createAttempt(notification);

    // 2. Build outbound message
    OutboundMessage msg = buildMessage(notification);

    // 3. Call provider (via ChannelSender — the varying step)
    SendResult result = sender.send(msg);

    // 4. Classify and record outcome (fenced write)
    outcomeRecorder.record(notification, attempt, result, workerId);
}
```

Steps 1, 2, and 4 are identical for every channel. Step 3 is delegated to the channel-specific
`ChannelSender`. This avoids duplicating the attempt-tracking and outcome-recording logic
across four channel implementations.

---

## 4. Fairness & Resource Management Patterns

### 4.1 Token bucket rate limiting (two-layer)

**Problem:** A tenant sending 100K notifications per second would overwhelm the shared email
provider (which has its own rate limits) and starve other tenants.

**Solution:** Two independent token bucket layers, both checked before claiming work:

```
Layer 1: Tenant bucket     → "tenant A can send at most 100/s with burst 200"
Layer 2: Channel bucket    → "total EMAIL sends across ALL tenants: 500/s with burst 1000"

granted = min(tenant.tryAcquire(quantum), channel.tryAcquire(tenantGranted))
```

**Why token bucket over fixed window or sliding window?**
- Fixed window has boundary spikes (200 requests at 0:59 + 200 at 1:00 = 400 in 1 second)
- Sliding window requires storing timestamps for every request
- Token bucket smoothly refills at a constant rate, handles bursts up to the burst limit,
  and is O(1) in both time and space (one counter + one timestamp)

**Implementation detail:** `tryAcquire(n)` returns the number of tokens actually granted
(0 to n), not a boolean. This lets the caller gracefully degrade: if the tenant wants 20
but only 8 tokens are available, we claim 8 rows instead of claiming 0 and wasting the tick.

---

### 4.2 Weighted round-robin fairness (deficit-based)

**Problem:** Tenant A has 100K queued notifications, tenant B has 10. A naive "process all
of A, then all of B" means B waits hours behind A's backlog.

**Solution:** `FairTenantSelector` implements deficit-style weighted round-robin:

1. Each tick, it iterates tenants in a rotating order (cursor advances each tick)
2. Each tenant gets a quantum of `BASE_QUANTUM × weight` per round
3. A paid tenant with weight=3 gets 3× the throughput of a free tenant with weight=1
4. But both get served every round — B's 10 notifications go out in the first round,
   while A's 100K are metered over many rounds

**Why not priority queues?** Priority queues starve low-priority tenants. Round-robin with
weights guarantees every tenant gets some throughput every round, proportional to their weight.

**Why unused quantum is not banked:** If tenant B had nothing to send in round 1, it doesn't
get double quantum in round 2. Banking creates unpredictable bursts that defeat rate limiting.

---

### 4.3 Bounded thread pools with back-pressure

**Problem:** If the dispatcher claims 1,000 rows and submits them all to the thread pool, but
the pool can only run 8 at a time, the remaining 992 sit in the pool's queue consuming memory.
If the queue is unbounded, an OOM crash is one traffic spike away.

**Solution:** Each channel gets a `ThreadPoolExecutor` with bounded core threads AND bounded queue:

```yaml
EMAIL:  { core: 8, max: 8, queue: 200 }
SMS:    { core: 4, max: 4, queue: 100 }
```

When the queue is full, `pool.submit()` throws `RejectedExecutionException`. The dispatcher
catches this and releases the notification back to PENDING — no attempt consumed, no retry
penalty, no data loss.

```java
try {
    pool.submit(new DeliveryWorker(notification, sender, ...));
} catch (RejectedExecutionException e) {
    // Pool full — release row back to PENDING, don't consume an attempt
    releaseToPool(notification);
}
```

**Why bounded queue over CallerRunsPolicy?** CallerRunsPolicy would make the dispatcher thread
itself send the notification, blocking the tick loop and stalling all other tenants/channels.
Releasing to PENDING preserves fairness — the row will be picked up next tick.

---

## 5. API Design Patterns

### 5.1 Tenant-scoped data isolation (structural, not query-based)

**Problem:** Tenant admin A must never see tenant B's templates, notifications, or API keys.
A query-level filter (`WHERE tenant_id = ?`) works but is fragile — one forgotten filter
and data leaks.

**Solution:** The tenant ID is never taken from the URL path or request body. It's always
derived from the authenticated principal:

```java
public class CurrentTenant {
    public static UUID resolve() {
        TenantPrincipal p = (TenantPrincipal) SecurityContextHolder
            .getContext().getAuthentication().getPrincipal();
        return p.getTenantId();
    }
}
```

Every repository query includes `tenant_id = CurrentTenant.resolve()`. A tenant admin
requesting `/notifications/some-uuid` where that UUID belongs to another tenant gets 404
(not 403), because the query is `WHERE id = ? AND tenant_id = ?` — the row simply doesn't
exist in their view.

**What it prevents:**
- IDOR (insecure direct object reference): structurally impossible
- Forgot-the-filter bugs: the tenant ID comes from auth, not from the request
- Information leakage via error codes: 404 reveals nothing about other tenants

---

### 5.2 Idempotency-Key header with hash verification

**Problem:** The tenant's backend needs retry safety on the submit API. But just deduplicating
on the key isn't enough — what if they accidentally reuse a key for a different notification?

**Solution:** Two-field scheme:

| Scenario | Same idempotency key? | Same payload hash? | Response |
|----------|----------------------|-------------------|----------|
| New request | No | — | 202 Accepted |
| Retry of same request | Yes | Yes | 200 OK (return existing) |
| Key reuse with different payload | Yes | No | 422 Unprocessable |
| Race between identical requests | Yes (constraint) | Yes | Loser returns winner's row |

**Why not just use the key alone?** Returning 200 for a reused key with different content would
silently drop the second notification. The hash catches this as a client bug.

---

### 5.3 RFC 7807 ProblemDetail for errors

**Problem:** Inconsistent error formats make API integration painful. Some endpoints return
`{"message": "..."}`, others return `{"error": "...", "status": 400}`, others return plain text.

**Solution:** Every error response uses Spring's `ProblemDetail` (RFC 7807):

```json
{
    "type": "about:blank",
    "title": "Bad Request",
    "status": 400,
    "detail": "Template 'order_confirm' not found for channel SMS",
    "instance": "/api/v1/notifications"
}
```

A single `@RestControllerAdvice` maps every exception type to the right status code and detail
message. The tenant's backend can always parse `status` and `detail` regardless of which
endpoint threw the error.

---

## 6. Testing Patterns

### 6.1 Injectable Clock for deterministic time

**Problem:** Tests that depend on real time are flaky. Scheduled sends, backoff delays, lease
expiry, and token bucket refills all depend on "now." Using `Instant.now()` means races,
`Thread.sleep()`, and non-reproducible failures.

**Solution:** Every class that needs time injects a `Clock` bean:
- Production: `Clock.systemUTC()`
- Tests: `MutableClock` with `advance(Duration)` and `setInstant(Instant)` methods

Combined with manual dispatcher ticks (`scheduler.tick()` instead of `@Scheduled`), tests
control both time and execution order. No `Thread.sleep`, no Awaitility timeouts for
time-dependent behavior — only for async worker thread completion.

---

### 6.2 ProgrammableSender for deterministic provider behavior

**Problem:** Tests need to assert exact retry counts, backoff spacing, and state transitions.
Random-failure mocks make assertions non-deterministic.

**Solution:** `ProgrammableSender` lets tests script exact per-notification responses:

```java
sender.program(notificationId,
    new Transient("TIMEOUT", "timed out"),      // attempt 1
    new Transient("TIMEOUT", "timed out"),      // attempt 2
    new Success("msg-123")                       // attempt 3
);
```

After the dispatcher runs, the test asserts: `attemptCount == 3`, `status == SENT`,
and the backoff intervals match the policy.

---

### 6.3 Testcontainers for real DB behavior

**Problem:** H2 (in-memory DB) doesn't support `FOR UPDATE SKIP LOCKED`, partial indexes,
or `jsonb`. Tests using H2 would pass but the code would fail in production.

**Solution:** `@Testcontainers` with `PostgreSQLContainer("postgres:16-alpine")`. Every
integration test runs against real PostgreSQL with real Flyway migrations. The container
starts once per test class (via `@Container static final`), not per test method.

**What this catches that H2 misses:**
- `SKIP LOCKED` behavior (H2 ignores it silently)
- `jsonb` column operations
- Partial index usage
- Unique constraint race conditions under concurrent inserts

---

### 6.4 Manual tick + Awaitility for async assertions

**Problem:** The dispatcher runs work asynchronously on thread pools. After `scheduler.tick()`,
the work isn't done yet — it's just submitted. We need to wait for completion without
`Thread.sleep`.

**Solution:** Two-step pattern:

```java
scheduler.tick();                              // submit work to pools
await().atMost(2, SECONDS).untilAsserted(() -> // poll until assertion passes
    assertThat(repo.findById(id).get().getStatus()).isEqualTo(SENT)
);
```

`Awaitility` polls the assertion every 100ms (default). If it passes, the test continues
immediately. If 2 seconds pass, it fails with the last assertion error. This is both fast
(no unnecessary waiting) and reliable (no race conditions).

---

## 7. Data Patterns

### 7.1 Template rendering at accept time (snapshot)

**Problem:** If we render templates at send time, a template edit between submission and
delivery changes what the recipient sees. Worse, if a variable is removed from the template
between submission and retry, the retry fails with "missing variable" even though the
original submission was valid.

**Solution:** Templates are rendered at ingestion time. The rendered `subject` and `body` are
stored on the notification row. Template edits create a new version — existing queued
notifications keep their snapshot.

**What it prevents:**
- Non-deterministic retries (same notification always sends the same content)
- Missing-variable failures on retry
- Retroactive content changes to queued notifications

---

### 7.2 Event sourcing lite — NotificationEvent audit trail

**Problem:** We need to answer "what happened to this notification and when?" for debugging,
compliance, and tenant-facing delivery reports.

**Solution:** Every state transition inserts a `NotificationEvent` row in the same transaction
as the status update. The event records: `from_status`, `to_status`, `reason`, `actor`,
`occurred_at`.

This isn't full event sourcing (we don't rebuild state from events), but it gives us a
complete, ordered, immutable timeline for every notification. The notification row has the
current state; the events table has the history.

```
notification 7a3f...
├── PENDING    → PROCESSING   reason=claimed          actor=DISPATCHER  00:01.000
├── PROCESSING → RETRYING     reason=TIMEOUT          actor=DISPATCHER  00:01.050
├── RETRYING   → PROCESSING   reason=claimed          actor=DISPATCHER  00:03.120
└── PROCESSING → SENT         reason=provider_success actor=DISPATCHER  00:03.180
```

---

### 7.3 Partial indexes for queue performance

**Problem:** The notification table will have millions of rows (mostly SENT or FAILED). The
claim query only cares about the few thousand that are PENDING, SCHEDULED, or RETRYING.
A full B-tree index on `(tenant_id, channel, next_attempt_at)` includes all the terminal
rows — wasted space and slower scans.

**Solution:** Partial index:

```sql
CREATE INDEX idx_notification_claim
ON notification (tenant_id, channel, next_attempt_at)
WHERE status IN ('SCHEDULED', 'PENDING', 'RETRYING');
```

This index only contains rows eligible for claiming. As notifications reach terminal states,
they drop out of the index. The claim query scans a small, hot index instead of a large,
cold one.

**Impact:** On a table with 10M rows where 99% are terminal, this index is ~100× smaller
than the equivalent full index.

---

## 8. Configuration Patterns

### 8.1 @ConfigurationProperties with type safety

**Problem:** String-based `@Value("${notify.dispatcher.tick-interval}")` properties are
error-prone — typos compile fine and fail at runtime.

**Solution:** `DispatcherProperties` as a `@ConfigurationProperties(prefix = "notify.dispatcher")`
class with typed fields. Spring validates at startup — a missing or wrong-type property
fails fast with a clear error.

---

### 8.2 Profile-based bean switching

**Problem:** Production uses random-failure mock senders. Tests need deterministic
`ProgrammableSender`. Both implement `ChannelSender`.

**Solution:**
- `@Profile("!test")` on `MockEmailSender` — active in dev and prod
- `@Profile("test")` on `ProgrammableSender` beans — active only in tests
- Same interface, same registry, different behavior

The dispatcher and workers are completely unaware of which sender they're calling. Test
setup code programs the sender; everything else runs identically to production.

---

## 9. Pattern interaction map

These patterns don't work in isolation. Here's how they compose:

```
Submit API
  │
  ├─ Idempotency (unique constraint + hash)
  ├─ Template snapshot (render at accept time)
  └─ State machine (null → PENDING/SCHEDULED)
       │
       ▼
Dispatch tick
  │
  ├─ Fair selector (weighted round-robin)
  ├─ Token bucket (2-layer rate limit)
  ├─ Queue-in-DB (SKIP LOCKED claim)
  ├─ State machine (PENDING → PROCESSING)
  │    │
  │    ▼
  │  Bounded pool → DeliveryWorker
  │    │
  │    ├─ Strategy (ChannelSender per channel)
  │    ├─ Sealed result (Success | Transient | Permanent)
  │    ├─ Failure classifier (transient vs permanent)
  │    └─ Fenced outcome write (locked_by check)
  │         │
  │         ├─ State machine (PROCESSING → SENT/RETRYING/FAILED)
  │         └─ Audit event (NotificationEvent in same tx)
  │
  └─ Backoff policy (exponential + jitter → next_attempt_at)
       │
       ▼
Lease reaper
  │
  ├─ Lease check (locked_until < now)
  ├─ State machine (PROCESSING → RETRYING)
  └─ Audit event (reason=lease_expired, actor=REAPER)
```

Every arrow is a single transaction boundary. Every box is testable in isolation.
Every pattern solves exactly one failure mode.

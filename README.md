# Multi-tenant Notification Service

A high-throughput, multi-tenant notification service built with Spring Boot that supports multiple channels (EMAIL, SMS, PUSH, IN_APP), tenant-defined templates with variable substitution, scheduled and immediate sends, per-tenant rate limiting, retries with exponential backoff, fairness under load, and full delivery tracking with audit trails.

---

## Quick Start

### Prerequisites
- Java 21+
- PostgreSQL 16+ (running on `localhost:5433`)
- Docker (for Testcontainers in integration tests)

### Database Setup

```bash
# Create the database and user
psql -U postgres -c "CREATE USER notify WITH PASSWORD 'notify';"
psql -U postgres -c "CREATE DATABASE notify OWNER notify;"
```

### Run the Application

```bash
./gradlew bootRun
```

The app starts on `http://localhost:8080`. Flyway runs all migrations automatically and seeds dev data (2 tenants, admin users, templates, channel configs).

### Run Tests

```bash
./gradlew test
```

Tests use Testcontainers — Docker must be running. No local PostgreSQL needed for tests.

### Seed Credentials

| User | Password | Role | Tenant |
|------|----------|------|--------|
| `platform-admin` | `password123` | PLATFORM_ADMIN | — |
| `acme-admin` | `password123` | TENANT_ADMIN | Acme Corp |
| `globex-admin` | `password123` | TENANT_ADMIN | Globex Inc |

---

## Assumptions

| # | Assumption | Reasoning |
|---|-----------|-----------|
| A1 | Single application instance. Rate-limit token buckets are in-memory. | Brief puts distributed systems out of scope. `SKIP LOCKED` claiming is multi-instance safe, but token buckets would need Redis/DB at scale — documented as first thing to change. |
| A2 | PostgreSQL is both the system of record and the work queue. | `FOR UPDATE SKIP LOCKED` gives safe concurrent claiming without extra infra (no Kafka, no Redis). One source of truth, one transaction boundary. |
| A3 | Notifications are submitted by the tenant's backend via API key (`X-API-Key`), not by a human admin. | The two roles in the brief are administrative. API keys represent "the tenant's application" as the machine caller. |
| A4 | One notification = one recipient × one channel. | Keeps state, retries, and audit per delivery unit simple and unambiguous. |
| A5 | Templates are rendered at accept time. The rendered subject/body are snapshotted on the notification. | Deterministic retries — template edits create a new version and never affect queued notifications. Missing variables fail fast with 400, not silently during dispatch. |
| A6 | Delivery guarantee is at-least-once to the provider, effectively-once to the recipient. | True exactly-once across a network boundary is impossible. The notification ID is passed to the provider as its idempotency key, and mock providers deduplicate on it. |
| A7 | Rate-limited work is deferred, not failed. Does not consume retry attempts. | Rate limiting is back-pressure, not an error. |
| A8 | `SENT` is terminal. Provider delivery receipts (DELIVERED/BOUNCED webhooks) are an extension point. | Mock providers have no async receipts. The design accommodates adding DELIVERED/BOUNCED states later. |
| A9 | Authentication: HTTP Basic (BCrypt) for admins, SHA-256 hashed API keys for senders. | "Basic RBAC" per the brief. Advanced auth (OAuth, SSO) is explicitly out of scope. |
| A10 | All times stored in UTC (`TIMESTAMPTZ`). `scheduledAt` must be ISO-8601 and ≤ 30 days ahead. | Avoids timezone ambiguity. Bounds the scheduled backlog. |

---

## Architecture Overview

### Notification State Machine

```
                         ┌──────────────────────────┐
                         │       SUBMIT API         │
                         └─────┬───────────┬────────┘
                               │           │
                        scheduledAt?    immediate
                               │           │
                               ▼           ▼
                        ┌───────────┐  ┌──────────┐
                        │ SCHEDULED │  │ PENDING  │◄── manual retry
                        └───┬──┬────┘  └───┬──┬───┘        ▲
                            │  │           │  │             │
                    due ────┘  │  claimed ─┘  │             │
                          cancel          cancel            │
                            │              │                │
                            ▼              ▼                │
                       ┌──────────┐  ┌────────────┐        │
                       │CANCELLED │  │ PROCESSING │────────┘
                       └──────────┘  └─┬──┬──┬──┬─┘  (pool reject)
                                       │  │  │  │
                        success ───────┘  │  │  └── permanent / exhausted
                        transient ────────┘  │
                                             │
                   ┌───────────┐       ┌──────────┐
                   │ RETRYING  │       │  FAILED  │
                   └─────┬─────┘       └──────────┘
                         │
                    backoff expired
                    → re-claimed
                         └──────► PROCESSING
```

**7 states, 12 legal transitions.** Every transition goes through a single `NotificationStateMachine.transition()` guard. Every transition writes a `NotificationEvent` audit row in the same transaction.

### Dispatch Pipeline

```
Tick (100ms)
  │
  ├─ LeaseReaper: recover stuck PROCESSING rows
  │
  ├─ FairTenantSelector: weighted round-robin ordering
  │
  └─ For each tenant × channel:
       ├─ TokenBucket check (tenant rate limit)
       ├─ TokenBucket check (global channel limit)
       ├─ WorkClaimer: SELECT FOR UPDATE SKIP LOCKED
       ├─ Submit to bounded ThreadPoolExecutor
       │    └─ DeliveryWorker: send → classify → record
       │         └─ OutcomeRecorder: fenced write (locked_by guard)
       └─ Release unused tokens on under-claim or pool rejection
```

### Duplicate-Delivery Defenses (5 layers)

| Layer | Mechanism | What it catches |
|-------|-----------|----------------|
| 1. Ingestion | `UNIQUE(tenant_id, idempotency_key)` + request hash | Duplicate API submits |
| 2. Claim | `SKIP LOCKED` + status predicate | Two workers claiming same row |
| 3. Outcome | Fenced by `locked_by` | Stale worker overwriting after lease expiry |
| 4. Provider | Notification ID as provider idempotency key | Duplicate calls to the same provider |
| 5. IN_APP | `UNIQUE(notification_id)` on `in_app_message` | Duplicate inbox entries |

### Key Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| Queue-in-DB (SKIP LOCKED) | WorkClaimer | No external queue infra; one source of truth |
| Lease + fenced writes | WorkClaimer + OutcomeRecorder | Recover from hung workers without duplicate sends |
| Sealed result type | SendResult | Compiler-enforced exhaustive handling of success/transient/permanent |
| Token bucket (2-layer) | RateLimiterRegistry | Tenant + channel rate limits, partial grants, never blocks |
| Weighted round-robin | FairTenantSelector | No tenant starves another under load |
| Exponential backoff + equal jitter | ExponentialBackoffWithJitter | Spreads retries to avoid thundering herd |
| Template snapshot at accept time | NotificationIngestionService | Deterministic retries; template edits don't affect queued work |
| Strategy | ChannelSender per channel | Adding WhatsApp = one new class, zero dispatcher changes |

---

## API Reference

### Platform Admin — `/api/v1/admin` (HTTP Basic, PLATFORM_ADMIN)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/tenants` | Create tenant |
| `GET` | `/tenants` | List tenants (paginated) |
| `GET` | `/tenants/{id}` | Get tenant |
| `PATCH` | `/tenants/{id}` | Update limits/weight |
| `POST` | `/tenants/{id}/suspend` | Suspend tenant |
| `POST` | `/tenants/{id}/activate` | Activate tenant |
| `POST` | `/tenants/{id}/admins` | Create tenant admin user |
| `GET` | `/tenants/{id}/admins` | List tenant admins |
| `GET` | `/global-limits` | List per-channel rate caps |
| `PUT` | `/global-limits/{channel}` | Update channel rate cap |
| `GET` | `/settings` | Get platform settings |
| `PUT` | `/settings` | Update platform settings |

### Tenant Admin — `/api/v1/tenant` (HTTP Basic, TENANT_ADMIN)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/templates` | Create template |
| `GET` | `/templates` | List templates (paginated) |
| `GET` | `/templates/{id}` | Get template |
| `PUT` | `/templates/{id}` | Update (creates new version) |
| `DELETE` | `/templates/{id}` | Deactivate |
| `POST` | `/templates/{id}/preview` | Render with sample variables |
| `GET` | `/channels` | List channel configs |
| `PUT` | `/channels/{channel}` | Enable/disable channel |
| `POST` | `/api-keys` | Issue API key (raw key shown once) |
| `GET` | `/api-keys` | List API keys |
| `DELETE` | `/api-keys/{id}` | Revoke API key |

### Send API — `/api/v1/notifications` (X-API-Key header)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/notifications` | Submit notification (Idempotency-Key required) |
| `GET` | `/notifications` | List notifications (filter: status, channel, paginated) |
| `GET` | `/notifications/{id}` | Detail with attempts + audit timeline |
| `POST` | `/notifications/{id}/cancel` | Cancel (SCHEDULED/PENDING only) |

### Idempotency semantics

| Scenario | Response |
|----------|----------|
| New key | 202 Accepted |
| Same key + same payload | 200 OK (returns existing) |
| Same key + different payload | 422 Unprocessable Entity |

---

## API Examples

### Create an API key

```bash
curl -s -X POST -u acme-admin:password123 \
  -H "Content-Type: application/json" \
  http://localhost:8080/api/v1/tenant/api-keys \
  -d '{"name": "My Key"}' | jq .rawKey
```

### Send a notification

```bash
curl -s -X POST \
  -H "X-API-Key: <your-key>" \
  -H "Idempotency-Key: unique-key-001" \
  -H "Content-Type: application/json" \
  http://localhost:8080/api/v1/notifications \
  -d '{
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "templateCode": "welcome",
    "variables": {"name": "Alice", "companyName": "Acme Corp"}
  }'
```

### Check delivery status

```bash
curl -s -H "X-API-Key: <your-key>" \
  http://localhost:8080/api/v1/notifications/<notification-id> | jq .
```

Response includes rendered snapshot, delivery attempts, and full audit timeline.

### Schedule a notification

```bash
curl -s -X POST \
  -H "X-API-Key: <your-key>" \
  -H "Idempotency-Key: sched-001" \
  -H "Content-Type: application/json" \
  http://localhost:8080/api/v1/notifications \
  -d '{
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "templateCode": "welcome",
    "variables": {"name": "Alice", "companyName": "Acme Corp"},
    "scheduledAt": "2026-10-01T09:00:00Z"
  }'
```

---

## Tech Stack

| Choice | Reason |
|--------|--------|
| Java 21, Spring Boot 3.x | Required stack. Records, sealed interfaces, pattern matching switch. |
| PostgreSQL 16 | `SKIP LOCKED` for safe concurrent claiming. Partial indexes. `jsonb` for flexible settings. |
| Flyway | Versioned, reviewable migrations. Schema is the source of truth (JPA validates, never generates). |
| JUnit 5 + Testcontainers | Integration tests run against real PostgreSQL — `SKIP LOCKED` and unique constraints can't be faked with H2. |
| Awaitility | Async assertions without `Thread.sleep`. Workers run on thread pools; Awaitility polls until assertions pass. |
| Injectable `Clock` | Every class injects a `Clock` bean. Tests use `MutableClock` with `advance()` for deterministic time control. |

---

## Testing Strategy

### Unit tests (no Spring context)

| Target | What's tested |
|--------|--------------|
| `TemplateRenderer` | Variable substitution, missing vars, HTML escaping, SMS length |
| `NotificationStateMachine` | Every legal transition allowed, every illegal one rejected |
| `ExponentialBackoffWithJitter` | Growth, cap, jitter bounds, determinism with seeded Random |
| `TokenBucket` | Burst, refill rate, partial grant, release, cap enforcement |
| `FairTenantSelector` | Weight × quantum, cursor rotation, empty input handling |
| `FailureClassifier` | Transient vs permanent mapping, unknown defaults to transient |
| `RequestHasher` | Stability across key order, null/empty handling |
| `RecipientValidator` | Email regex, E.164 phone, device token, user ID |

### Integration tests (Spring Boot + Testcontainers PostgreSQL)

| Scenario | What it proves |
|----------|---------------|
| **Concurrent idempotent submit** (20 threads) | Exactly 1 row created, all threads get same ID |
| **No double claim** (concurrent claimers) | Each row claimed exactly once via SKIP LOCKED |
| **Transient → retry → success** | Correct attempt count, backoff spacing, state transitions |
| **Retries exhausted** | FAILED after max attempts with correct reason |
| **Permanent failure** | FAILED after 1 attempt, no retry |
| **Lease recovery + fencing** | Reaper re-queues; stale worker write rejected |
| **Rate limit** | Tenant throughput capped at configured rate |
| **Fairness** | Small tenant finishes before large tenant is halfway done |
| **Pool back-pressure** | Rejected rows released to PENDING, no data loss |
| **Scheduled send** | Not dispatched before time, dispatched after |
| **Cancel** | SCHEDULED/PENDING → CANCELLED; SENT → 409 |
| **RBAC isolation** | Tenant A cannot see B's data; cross-role access blocked |

### Testing patterns

- **`MutableClock`** — fake clock with `advance(Duration)`. No `Thread.sleep` anywhere.
- **`ProgrammableSender`** — script exact per-notification provider responses for deterministic assertions.
- **Manual `scheduler.tick()`** — dispatcher auto-start disabled in tests. Tests control exactly when dispatch runs.
- **Testcontainers** — real PostgreSQL, real Flyway, real `SKIP LOCKED`. No H2 faking.

---

## Extensibility

| Extension | How the design accommodates it |
|-----------|-------------------------------|
| New channel (WhatsApp, Slack) | Add `ChannelSender` impl + pool config. Nothing else changes. |
| Real providers (SES, Twilio, FCM) | Swap mock senders. Error codes map through `FailureClassifier`. |
| Delivery receipts (DELIVERED/BOUNCED) | Webhook endpoint → new terminal states on top of SENT. |
| Multi-instance | Claiming is already safe. Move token buckets to Redis. |
| Priority lanes (OTP vs marketing) | `priority` column + ordered claim or separate pools. |
| Template localization | `locale` column on template, fallback chain. |

---

## Project Structure

### For integration testing make sure docker is up. 

```
src/main/java/com/shashank/notificationservice/
├── models/          — JPA entities
├── constants/       — Enums (Channel, NotificationStatus, Role, etc.)
├── configs/         — Spring configuration, properties
├── controllers/     — REST controllers
├── dtos/            — Request/response records
├── exceptions/      — Custom exceptions
├── repositories/    — Spring Data JPA repositories
├── security/        — Auth filters, TenantPrincipal, CurrentTenant
├── services/        — Business logic, dispatcher, providers
└── utils/           — TokenBucket, BackoffCalculator, FailureClassifier

src/main/resources/
├── application.yml
└── db/migration/    — Flyway migrations (V001–V099)


src/test/java/
├── support/         — MutableClock, ProgrammableSender, TestConfigs
├── unit/            — Pure unit tests (no Spring)
└── integration/     — Full Spring Boot + Testcontainers tests
```

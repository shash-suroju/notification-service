# CLAUDE.md — AI Working Agreement

> This file is the source of truth for Claude Code when working on this project.
> Read this FIRST before touching any code.

---

## Project context

**Multi-tenant notification service** — a take-home assignment.
Stack: Java 21, Spring Boot 3.3.5, PostgreSQL 16, Gradle, Flyway, Lombok, JUnit 5, Testcontainers.

Reference documents (read them if you need design context):
- `plan.md` — implementation plan with entities, state machine, queue/clock/mock details
- `design-patterns.md` — every pattern used and why
- `H-PLATFORM-ADMIN.md` — platform admin + tenant CRUD + RBAC spec
- `H-TEMPLATES.md` — template CRUD + renderer spec
- `H-INGESTION.md` — notification ingestion with idempotency spec
- `H-DISPATCHER.md` — dispatcher, workers, retry, rate limiting, fairness spec

---

## Build & test

```bash
./gradlew build          # compile + test
./gradlew test           # tests only (Testcontainers — needs Docker running)
./gradlew bootRun        # run locally (needs PostgreSQL on localhost:5432)
```

---

## Rules (always follow)

1. **Never use `Instant.now()` or `LocalDateTime.now()` or `System.currentTimeMillis()`.**
   Always inject the `Clock` bean and call `clock.instant()`.

2. **Never change notification status directly with `setStatus()`.**
   Always go through `NotificationStateMachine.transition()`.

3. **Every status transition MUST persist a `NotificationEvent` in the SAME transaction.**
   No silent state changes.

4. **All repository queries for tenant-scoped data MUST include `tenant_id`.**
   The tenant ID comes from `CurrentTenant.resolve()`, never from path params or request body.

5. **Use records for DTOs.** Request/response DTOs are Java records, not classes with getters/setters.

6. **Entities use `UUID` primary keys**, generated in Java via `UUID.randomUUID()` (not DB-generated).

7. **All timestamps are `Instant`** stored as `TIMESTAMPTZ` in PostgreSQL. No `LocalDateTime`.

8. **Tests must not use `Thread.sleep()`.** Use `MutableClock.advance()` for time-dependent behavior
   and `Awaitility` for async assertions.

9. **Commit after each logical unit.** Don't accumulate a giant changeset.

---

## Code style

- Package: `com.assignment.notificationservice`
- Group ID: `com.assignment`
- Artifact ID: `notification-service`
- Java 21 features: records, sealed interfaces, pattern matching switch, text blocks
- Lombok: used on entities (`@Getter`, `@Setter`, `@NoArgsConstructor`, `@RequiredArgsConstructor`)
- Spring conventions: constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` on fields)
- Enum storage: `@Enumerated(EnumType.STRING)` always, never ordinal
- JSON fields: `@JdbcTypeCode(SqlTypes.JSON)` + `@Column(columnDefinition = "jsonb")` with `String` type in entity
- Pagination: Spring `Pageable` with max size 100 enforced in controller
- Error responses: RFC 7807 `ProblemDetail` via `@RestControllerAdvice`

---

## Package layout

```
com.assignment.notificationservice
  configs/           — Spring @Configuration, @ConfigurationProperties
  constants/         — static final constants (ApiPaths, SecurityConstants, etc.)
  controllers/       — REST controllers
  dtos/              — request/response records
  exceptions/        — custom exceptions + GlobalExceptionHandler
  models/            — JPA entities
    enums/           — Channel, NotificationStatus, Role, TenantStatus, etc.
  repositories/      — Spring Data JPA repositories
  security/          — ApiKeyAuthFilter, CurrentTenant, TenantPrincipal
  services/          — business logic, state machine, dispatcher, senders
```

---

## Key components

- **NotificationStateMachine** (`services/`) — pure static utility guarding all status transitions
- **CurrentTenant** (`security/`) — resolves tenant ID from SecurityContext, never from URL/body
- **ClockConfig** (`configs/`) — production `Clock.systemUTC()`, tests override with `MutableClock`
- **ApiKeyAuthFilter** (`security/`) — extracts `X-API-Key` header, authenticates via prefix + SHA-256 hash
- **SecurityConfig** (`configs/`) — HTTP Basic for admin/tenant, API key for notification endpoints
- **DispatchScheduler** (`services/`) — tick-based dispatcher loop using `FOR UPDATE SKIP LOCKED`
- **FairTenantSelector** (`services/`) — weighted fair queuing across tenants
- **RateLimiterRegistry** (`services/`) — token-bucket rate limiting per tenant + global channel limits

---

## Database

- 12 Flyway migrations in `src/main/resources/db/migration/` (V001–V011 + V099 seed + V100)
- `V099__seed_dev_data.sql` seeds two tenants (Acme Corp, Globex Inc), users, templates, and an API key
- Schema uses `uuid` PKs, `TIMESTAMPTZ` timestamps, `VARCHAR` enums, `JSONB` for flexible fields
- Partial indexes on `notification` for claim queue and lease reaper

---

## Auth model (three audiences)

| Path prefix              | Auth method | Required role    |
|--------------------------|-------------|------------------|
| `/api/v1/admin/**`       | HTTP Basic  | PLATFORM_ADMIN   |
| `/api/v1/tenant/**`      | HTTP Basic  | TENANT_ADMIN     |
| `/api/v1/notifications/**` | X-API-Key | (any valid key)  |

---

## Test infrastructure

- `BaseIntegrationTest` — Testcontainers PostgreSQL, `MutableClock`, `TestRestTemplate`
- `BaseDispatcherTest` — extends base with `TestSenderConfig` for controllable mock senders
- `support/MutableClock` — deterministic clock for tests (`advance()`, `setInstant()`)
- `support/TestSender` / `ProgrammableSender` — controllable channel senders for dispatch tests
- `support/TestTenant` — helper to create test tenants with API keys
- Tests are in `unit/` (no Spring context) and `integration/` (full context) sub-packages

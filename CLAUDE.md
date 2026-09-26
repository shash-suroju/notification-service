# CLAUDE.md — AI Working Agreement

> This file is the source of truth for Claude Code when working on this project.
> Read this FIRST before touching any code.

---

## Project context

Building a **multi-tenant notification service** as a 48-hour take-home assignment.
Stack: Java 21, Spring Boot 3.x, PostgreSQL 16, Flyway, JUnit 5, Testcontainers.

Reference documents (read them if you need design context):
- `setup.md` — full design doc with domain model, API design, data flow
- `plan.md` — implementation plan with entities, state machine, queue/clock/mock details
- `design-patterns.md` — every pattern used and why

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

- Package: `com.shashank.notificationservice`
- Group ID: `com.shashank`
- Artifact ID: `notification-service`
- Java 21 features: records, sealed interfaces, pattern matching switch, text blocks
- Spring conventions: constructor injection (no `@Autowired` on fields), `@RequiredArgsConstructor` not available (no Lombok)
- Enum storage: `@Enumerated(EnumType.STRING)` always, never ordinal
- JSON fields: `@Column(columnDefinition = "jsonb")` with `String` type in entity, serialize/deserialize via Jackson in service layer
- Pagination: Spring `Pageable` with max size 100 enforced in controller
- Error responses: RFC 7807 `ProblemDetail` via `@RestControllerAdvice`

---

## H0-2 Implementation Spec

**Goal:** After this block, `./mvnw test` passes with:
- Spring context loads with Testcontainers PostgreSQL
- All 11 Flyway migrations apply cleanly
- Clock bean is injectable; MutableClock works in tests
- ProblemDetail handler returns RFC 7807 JSON
- Seed data loads in dev profile
- Basic smoke test confirms the app starts

### Step 1: Initialize Spring Boot project

Create a Spring Boot 3.3.x project with these dependencies in `pom.xml`:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<properties>
    <java.version>21</java.version>
    <testcontainers.version>1.20.4</testcontainers.version>
</properties>

<dependencies>
    <!-- Core -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- Database -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>

    <!-- Config -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>${testcontainers.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Step 2: Application configuration

**`src/main/resources/application.yml`**

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
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc.time_zone: UTC
        format_sql: true
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    default-property-inclusion: non_null
    serialization:
      write-dates-as-timestamps: false

server:
  error:
    include-message: always
    include-binding-errors: always

notify:
  dispatcher:
    auto-start: true
    tick-interval-ms: 100
    base-quantum: 20
    lease-duration-seconds: 60
    reaper-interval-seconds: 30
  pools:
    email:
      core-size: 8
      max-size: 8
      queue-capacity: 200
    sms:
      core-size: 4
      max-size: 4
      queue-capacity: 100
    push:
      core-size: 8
      max-size: 8
      queue-capacity: 200
    in-app:
      core-size: 2
      max-size: 2
      queue-capacity: 100
  retry:
    base-delay-ms: 2000
    max-delay-ms: 300000
    default-max-attempts: 5
  mock-providers:
    email:
      latency-ms: 50
      transient-failure-rate: 0.10
      permanent-failure-rate: 0.02
    sms:
      latency-ms: 80
      transient-failure-rate: 0.15
      permanent-failure-rate: 0.02
    push:
      latency-ms: 30
      transient-failure-rate: 0.05
      permanent-failure-rate: 0.01
```

**`src/test/resources/application-test.yml`**

```yaml
spring:
  jpa:
    show-sql: true

notify:
  dispatcher:
    auto-start: false
    tick-interval-ms: 100
    base-quantum: 5
    lease-duration-seconds: 5
    reaper-interval-seconds: 1
  pools:
    email:
      core-size: 2
      max-size: 2
      queue-capacity: 20
    sms:
      core-size: 2
      max-size: 2
      queue-capacity: 20
    push:
      core-size: 2
      max-size: 2
      queue-capacity: 20
    in-app:
      core-size: 1
      max-size: 1
      queue-capacity: 10
  retry:
    base-delay-ms: 1000
    max-delay-ms: 10000
    default-max-attempts: 3
  mock-providers:
    email:
      latency-ms: 0
      transient-failure-rate: 0.0
      permanent-failure-rate: 0.0
    sms:
      latency-ms: 0
      transient-failure-rate: 0.0
      permanent-failure-rate: 0.0
    push:
      latency-ms: 0
      transient-failure-rate: 0.0
      permanent-failure-rate: 0.0
```

### Step 3: Flyway migrations

Create all migrations under `src/main/resources/db/migration/`.

**CRITICAL SQL RULES:**
- Use `UUID` for all primary keys (stored as `uuid` type in PostgreSQL)
- Use `TIMESTAMPTZ` for all timestamps (not `TIMESTAMP`)
- Use `VARCHAR` with length for constrained strings, `TEXT` for unconstrained
- Enum columns are `VARCHAR(30)` storing the Java enum name
- All foreign keys have `ON DELETE CASCADE` unless noted otherwise
- Create indexes explicitly — don't rely on JPA auto-generation

**V001__create_tenant.sql**

```sql
CREATE TABLE tenant (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    rate_limit_per_sec  INT      NOT NULL DEFAULT 100,
    burst           INT          NOT NULL DEFAULT 200,
    weight          INT          NOT NULL DEFAULT 1,
    max_attempts    INT          NOT NULL DEFAULT 5,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_tenant_name UNIQUE (name),
    CONSTRAINT uq_tenant_slug UNIQUE (slug),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_tenant_rate CHECK (rate_limit_per_sec > 0),
    CONSTRAINT ck_tenant_burst CHECK (burst >= rate_limit_per_sec),
    CONSTRAINT ck_tenant_weight CHECK (weight BETWEEN 1 AND 10),
    CONSTRAINT ck_tenant_attempts CHECK (max_attempts BETWEEN 1 AND 20)
);
```

**V002__create_app_user.sql**

```sql
CREATE TABLE app_user (
    id              UUID PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    tenant_id       UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_user_username UNIQUE (username),
    CONSTRAINT ck_user_role CHECK (role IN ('PLATFORM_ADMIN', 'TENANT_ADMIN')),
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_tenant ON app_user(tenant_id) WHERE tenant_id IS NOT NULL;
```

**V003__create_api_key.sql**

```sql
CREATE TABLE api_key (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    prefix          VARCHAR(8)   NOT NULL,
    key_hash        VARCHAR(64)  NOT NULL,
    name            VARCHAR(100),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ,

    CONSTRAINT ck_apikey_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT fk_apikey_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX idx_apikey_prefix ON api_key(prefix);
CREATE INDEX idx_apikey_tenant ON api_key(tenant_id);
```

**V004__create_global_channel_limit.sql**

```sql
CREATE TABLE global_channel_limit (
    channel         VARCHAR(20)  PRIMARY KEY,
    rate_per_sec    INT          NOT NULL DEFAULT 500,
    burst           INT          NOT NULL DEFAULT 1000,

    CONSTRAINT ck_gcl_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_gcl_rate CHECK (rate_per_sec > 0),
    CONSTRAINT ck_gcl_burst CHECK (burst >= rate_per_sec)
);

-- Seed default global limits
INSERT INTO global_channel_limit (channel, rate_per_sec, burst) VALUES
    ('EMAIL',  500, 1000),
    ('SMS',    200, 400),
    ('PUSH',   500, 1000),
    ('IN_APP', 1000, 2000);
```

**V005__create_platform_setting.sql**

```sql
CREATE TABLE platform_setting (
    key             VARCHAR(100) PRIMARY KEY,
    value           VARCHAR(500) NOT NULL
);

INSERT INTO platform_setting (key, value) VALUES
    ('max_tenant_rate_per_sec', '1000'),
    ('default_max_attempts', '5');
```

**V006__create_channel_config.sql**

```sql
CREATE TABLE channel_config (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    settings        JSONB        NOT NULL DEFAULT '{}',

    CONSTRAINT uq_channelconfig_tenant_channel UNIQUE (tenant_id, channel),
    CONSTRAINT ck_cc_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT fk_cc_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);
```

**V007__create_template.sql**

```sql
CREATE TABLE template (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    code            VARCHAR(100) NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    subject         VARCHAR(500),
    body            TEXT         NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_template_version UNIQUE (tenant_id, code, channel, version),
    CONSTRAINT ck_tmpl_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT fk_tmpl_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX idx_template_lookup ON template(tenant_id, code, channel, active);
```

**V008__create_notification.sql**

```sql
CREATE TABLE notification (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    channel             VARCHAR(20)  NOT NULL,
    recipient           VARCHAR(500) NOT NULL,

    -- idempotency
    idempotency_key     VARCHAR(255) NOT NULL,
    request_hash        VARCHAR(64)  NOT NULL,

    -- template snapshot
    template_id         UUID,
    template_version    INT,
    subject             VARCHAR(500),
    body                TEXT         NOT NULL,
    variables           JSONB,

    -- state
    status              VARCHAR(20)  NOT NULL,

    -- scheduling + queue
    scheduled_at        TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ  NOT NULL,

    -- retry
    attempt_count       INT          NOT NULL DEFAULT 0,
    max_attempts        INT          NOT NULL DEFAULT 5,

    -- lease
    locked_by           VARCHAR(50),
    locked_until        TIMESTAMPTZ,

    -- outcome
    last_error_code     VARCHAR(50),
    failure_reason      VARCHAR(500),
    sent_at             TIMESTAMPTZ,

    -- audit
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_notif_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_notif_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_notif_status CHECK (status IN (
        'SCHEDULED', 'PENDING', 'PROCESSING', 'RETRYING', 'SENT', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_notif_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_notif_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_notif_template FOREIGN KEY (template_id) REFERENCES template(id)
);

-- THE critical index: only rows eligible for claiming are indexed
CREATE INDEX idx_notification_claim
    ON notification (tenant_id, channel, next_attempt_at)
    WHERE status IN ('SCHEDULED', 'PENDING', 'RETRYING');

-- For the lease reaper
CREATE INDEX idx_notification_lease
    ON notification (status, locked_until)
    WHERE status = 'PROCESSING';

-- For delivery reports
CREATE INDEX idx_notification_tenant_status
    ON notification (tenant_id, status, created_at);
```

**V009__create_delivery_attempt.sql**

```sql
CREATE TABLE delivery_attempt (
    id                  UUID PRIMARY KEY,
    notification_id     UUID         NOT NULL,
    attempt_no          INT          NOT NULL,
    started_at          TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ,
    outcome             VARCHAR(30),
    error_code          VARCHAR(50),
    error_message       VARCHAR(500),
    provider_message_id VARCHAR(255),
    latency_ms          BIGINT,

    CONSTRAINT uq_attempt UNIQUE (notification_id, attempt_no),
    CONSTRAINT ck_attempt_outcome CHECK (outcome IS NULL OR outcome IN (
        'SUCCESS', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE', 'ABANDONED'
    )),
    CONSTRAINT fk_attempt_notif FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE
);
```

**V010__create_notification_event.sql**

```sql
CREATE TABLE notification_event (
    id                  UUID PRIMARY KEY,
    notification_id     UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    from_status         VARCHAR(20),
    to_status           VARCHAR(20)  NOT NULL,
    reason              VARCHAR(255),
    actor               VARCHAR(20)  NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_event_actor CHECK (actor IN ('API', 'DISPATCHER', 'REAPER', 'ADMIN')),
    CONSTRAINT fk_event_notif FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE
);

CREATE INDEX idx_event_notification ON notification_event(notification_id, occurred_at);
CREATE INDEX idx_event_tenant ON notification_event(tenant_id, occurred_at);
```

**V011__create_in_app_message.sql**

```sql
CREATE TABLE in_app_message (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    recipient           VARCHAR(500) NOT NULL,
    notification_id     UUID         NOT NULL,
    title               VARCHAR(500),
    body                TEXT         NOT NULL,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_inapp_notification UNIQUE (notification_id),
    CONSTRAINT fk_inapp_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
    CONSTRAINT fk_inapp_notif FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE
);

CREATE INDEX idx_inapp_recipient ON in_app_message(tenant_id, recipient, created_at DESC);
```

**V099__seed_dev_data.sql**

```sql
-- This migration seeds dev/demo data. In production, skip this or use a separate profile.
-- Password for all users: "password123" (BCrypt hash below)

-- Platform admin
INSERT INTO app_user (id, username, password_hash, role, tenant_id) VALUES
    ('00000000-0000-0000-0000-000000000001', 'platform-admin',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'PLATFORM_ADMIN', NULL);

-- Tenant A: Acme Corp
INSERT INTO tenant (id, name, slug, status, rate_limit_per_sec, burst, weight, max_attempts) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Acme Corp', 'acme', 'ACTIVE', 100, 200, 1, 5);

INSERT INTO app_user (id, username, password_hash, role, tenant_id) VALUES
    ('00000000-0000-0000-0000-000000000010', 'acme-admin',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'TENANT_ADMIN', '10000000-0000-0000-0000-000000000001');

INSERT INTO channel_config (id, tenant_id, channel, enabled) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'EMAIL', true),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'SMS', true),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'PUSH', true),
    ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', 'IN_APP', true);

INSERT INTO template (id, tenant_id, code, channel, version, subject, body) VALUES
    ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'welcome', 'EMAIL', 1, 'Welcome to {{companyName}}!',
     'Hello {{name}}, welcome to {{companyName}}! Your account is ready.'),
    ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     'order_shipped', 'SMS', 1, NULL,
     'Hi {{name}}, your order {{orderId}} has shipped! Track: {{trackingUrl}}'),
    ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001',
     'payment_received', 'PUSH', 1, 'Payment received',
     'We received your payment of {{amount}}. Thank you!');

-- API key for Acme: prefix "acme1234", full key hash is of "ntfy_acme1234_testkey1234567890"
-- SHA-256 of the above: precomputed (you must compute the real hash at runtime or use a known test value)
INSERT INTO api_key (id, tenant_id, prefix, key_hash, name, status) VALUES
    ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'acme1234', 'PLACEHOLDER_HASH_REPLACE_AT_STARTUP', 'Test Key', 'ACTIVE');

-- Tenant B: Globex Inc
INSERT INTO tenant (id, name, slug, status, rate_limit_per_sec, burst, weight, max_attempts) VALUES
    ('10000000-0000-0000-0000-000000000002', 'Globex Inc', 'globex', 'ACTIVE', 50, 100, 1, 3);

INSERT INTO app_user (id, username, password_hash, role, tenant_id) VALUES
    ('00000000-0000-0000-0000-000000000020', 'globex-admin',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'TENANT_ADMIN', '10000000-0000-0000-0000-000000000002');

INSERT INTO channel_config (id, tenant_id, channel, enabled) VALUES
    ('20000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000002', 'EMAIL', true),
    ('20000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000002', 'SMS', false),
    ('20000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000002', 'IN_APP', true);

INSERT INTO template (id, tenant_id, code, channel, version, subject, body) VALUES
    ('30000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000002',
     'alert', 'EMAIL', 1, 'Alert: {{alertType}}',
     'Hello {{name}}, an alert of type {{alertType}} was triggered: {{message}}');
```

### Step 4: Core enums

Create these enums under `com.shashank.notificationservice`:

**`common/Channel.java`**
```java
public enum Channel {
    EMAIL, SMS, PUSH, IN_APP
}
```

**`tenant/entity/TenantStatus.java`**
```java
public enum TenantStatus {
    ACTIVE, SUSPENDED
}
```

**`user/entity/Role.java`**
```java
public enum Role {
    PLATFORM_ADMIN, TENANT_ADMIN
}
```

**`notification/entity/NotificationStatus.java`**
```java
public enum NotificationStatus {
    SCHEDULED, PENDING, PROCESSING, RETRYING, SENT, FAILED, CANCELLED
}
```

**`notification/entity/AttemptOutcome.java`**
```java
public enum AttemptOutcome {
    SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE, ABANDONED
}
```

**`apikey/entity/ApiKeyStatus.java`**
```java
public enum ApiKeyStatus {
    ACTIVE, REVOKED
}
```

### Step 5: JPA entities

Create all JPA entities matching the Flyway schema exactly. Follow these rules:
- `@Entity` + `@Table(name = "...")` on every entity
- UUID primary key with `@Id` (no `@GeneratedValue` — set in constructor or builder)
- `@Enumerated(EnumType.STRING)` on every enum field
- `@ManyToOne(fetch = FetchType.LAZY)` on all relationships
- `@Column(columnDefinition = "jsonb")` on JSON fields
- `@Version` only on `Tenant` entity (optimistic locking)
- Constructor that generates UUID: `this.id = UUID.randomUUID();`
- Protected no-arg constructor for JPA

Entities to create (file per entity):
1. `tenant/entity/Tenant.java`
2. `user/entity/AppUser.java`
3. `apikey/entity/ApiKey.java`
4. `channel/entity/GlobalChannelLimit.java`
5. `channel/entity/PlatformSetting.java` (or in its own `platform` package)
6. `channel/entity/ChannelConfig.java`
7. `template/entity/Template.java`
8. `notification/entity/Notification.java`
9. `notification/entity/DeliveryAttempt.java`
10. `notification/entity/NotificationEvent.java`
11. `notification/entity/InAppMessage.java`

### Step 6: ClockConfig + MutableClock

**`config/ClockConfig.java`**
```java
@Configuration
public class ClockConfig {
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

**`src/test/java/.../support/MutableClock.java`**
```java
public class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
    }

    public MutableClock() {
        this(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Override public Instant instant() { return instant.get(); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return this; }

    public Instant advance(Duration d) {
        return instant.updateAndGet(i -> i.plus(d));
    }

    public void setInstant(Instant i) {
        instant.set(i);
    }
}
```

**`src/test/java/.../support/TestClockConfig.java`**
```java
@TestConfiguration
public class TestClockConfig {
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    public Clock clock(MutableClock mutableClock) {
        return mutableClock;
    }
}
```

### Step 7: GlobalExceptionHandler

**`common/GlobalExceptionHandler.java`**

Handle these exception types:
- `EntityNotFoundException` → 404
- `ConflictException` → 409
- `IllegalStateException` (state machine) → 409
- `MethodArgumentNotValidException` → 400 with field errors in detail
- `ConstraintViolationException` → 400
- `DataIntegrityViolationException` → 409
- `OptimisticLockingFailureException` → 409
- `AccessDeniedException` → 403
- Catch-all `Exception` → 500

All responses use `ProblemDetail.forStatusAndDetail(status, message)`.

Create custom exceptions in `common/exception/`:
- `EntityNotFoundException extends RuntimeException`
- `ConflictException extends RuntimeException`

### Step 8: SecurityConfig (permit all for now)

**`config/SecurityConfig.java`**

For H0-2, configure Spring Security to permit all requests. RBAC enforcement
comes in H2-5. But set up the structure:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### Step 9: NotificationStateMachine

**`notification/statemachine/NotificationStateMachine.java`**

Implement the state machine guard as specified in `plan.md` section 3.3.
This is a pure static utility — no Spring dependencies, easily unit-testable.

Allowed transitions (whitelist):
```
SCHEDULED  → {PENDING, CANCELLED}
PENDING    → {PROCESSING, CANCELLED}
PROCESSING → {SENT, RETRYING, FAILED, PENDING}
RETRYING   → {PROCESSING}
FAILED     → {PENDING}
```

### Step 10: DispatcherProperties

**`config/DispatcherProperties.java`**

`@ConfigurationProperties(prefix = "notify.dispatcher")` with fields:
- `boolean autoStart`
- `long tickIntervalMs`
- `int baseQuantum`
- `long leaseDurationSeconds`
- `long reaperIntervalSeconds`

Register it with `@EnableConfigurationProperties(DispatcherProperties.class)` on the main application class or a config class.

Similarly create:
- `config/PoolProperties.java` — per-channel pool sizes
- `config/RetryProperties.java` — base delay, max delay, default max attempts
- `config/MockProviderProperties.java` — per-channel latency and failure rates

### Step 11: BaseIntegrationTest

**`src/test/java/.../BaseIntegrationTest.java`**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestClockConfig.class)
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
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected MutableClock clock;
}
```

### Step 12: Smoke test

**`src/test/java/.../ApplicationSmokeTest.java`**

```java
class ApplicationSmokeTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void clockBeanIsMutableInTests() {
        Instant before = clock.instant();
        clock.advance(Duration.ofMinutes(5));
        Instant after = clock.instant();
        assertThat(after).isAfter(before);
        assertThat(Duration.between(before, after)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void flywayMigrationsApplied() {
        // If we got here, all migrations ran successfully
        assertThat(context.getBean(javax.sql.DataSource.class)).isNotNull();
    }
}
```

### Step 13: NotificationStateMachine unit test

**`src/test/java/.../unit/NotificationStateMachineTest.java`**

Test every legal transition succeeds and every illegal transition throws.
This test does NOT need Spring context or database — pure unit test.

```java
class NotificationStateMachineTest {
    // Test all 11 legal transitions pass
    // Test all illegal transitions throw IllegalStateException
    // e.g., SENT → PROCESSING, CANCELLED → PENDING, PENDING → SENT, etc.
}
```

---

## Commit message for H0-2

```
chore: project skeleton, Flyway schema, test infra

- Spring Boot 3.3 + Java 21 project structure
- 11 Flyway migrations + dev seed data (V099)
- All JPA entities matching schema
- ClockConfig + MutableClock for deterministic tests
- NotificationStateMachine with transition whitelist
- GlobalExceptionHandler (RFC 7807 ProblemDetail)
- SecurityConfig (permit-all, RBAC comes in H2-5)
- DispatcherProperties + pool/retry/mock config
- BaseIntegrationTest with Testcontainers PostgreSQL
- Smoke test: context loads, clock works, migrations applied
- NotificationStateMachine unit test: all transitions
```

---

## What comes AFTER H0-2

Do NOT implement these yet. They are documented here for context only:
- H2-5: HTTP Basic auth + API key filter, tenant CRUD, RBAC enforcement
- H5-8: Templates, renderer, channel configs, API key management
- H8-11: Notification ingestion with idempotency
- H11-16: Dispatcher core (claim, pools, workers, outcome recorder)
- H16-19: Retry + rate limits + fairness
- H19-21: Reports + remaining tests
- H21-23: README + docs
- H23-24: Loom video

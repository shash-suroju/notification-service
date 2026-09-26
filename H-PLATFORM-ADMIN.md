# H-PLATFORM-ADMIN — Platform Admin Controller + Tenant Management

> Claude Code: read this file and CLAUDE.md (for rules).
> Prerequisites: H0-2 (skeleton, entities, schema) and H-TEMPLATES (security, RBAC) are complete.
> The Tenant entity, TenantRepository, AppUser entity, AppUserRepository, SecurityConfig,
> and PasswordEncoder bean already exist.

---

## IMPORTANT: Repo package structure

Flat packages — NO new sub-packages:

```
models/          ← JPA entities (Tenant, AppUser already here)
configs/         ← @Configuration, @ConfigurationProperties
controllers/     ← @RestController (PlatformAdminController goes here)
dtos/            ← request/response records
exceptions/      ← custom exceptions
constants/       ← enums (TenantStatus, Role already here)
repositories/    ← JPA repositories (TenantRepository, AppUserRepository already here)
security/        ← auth filters, TenantPrincipal, CurrentTenant
services/        ← TenantService, GlobalLimitService, RateLimiterRegistry
utils/           ← pure utilities
```

---

## What this block delivers

1. **Tenant CRUD** — create, list, get, update limits/weight
2. **Tenant lifecycle** — suspend / activate
3. **Tenant admin user creation** — platform admin creates admin users for tenants
4. **Global channel limits** — list / update per-channel rate caps
5. **Platform settings** — get / update (max_tenant_rate_per_sec, default_max_attempts)
6. **Integration tests** — full CRUD, suspend/activate, validation, RBAC

Commit message:
```
feat: platform admin — tenant CRUD, global limits, lifecycle

- PlatformAdminController: tenant CRUD, suspend/activate, admin user creation
- GlobalLimitController: per-channel rate caps
- TenantService: create, update, suspend, activate with validation
- Channel config auto-seeding on tenant creation
- Integration tests: CRUD, lifecycle, RBAC enforcement
```

---

## 1. DTOs

### Tenant DTOs

**`dtos/CreateTenantRequest.java`**
```java
public record CreateTenantRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9-]+$",
        message = "Slug must be lowercase alphanumeric with hyphens only") String slug,
    @Min(1) int rateLimitPerSec,
    @Min(1) int burst,
    @Min(1) @Max(10) int weight,
    @Min(1) @Max(20) int maxAttempts
) {}
```

**`dtos/UpdateTenantRequest.java`**
```java
public record UpdateTenantRequest(
    @Size(max = 255) String name,
    @Min(1) Integer rateLimitPerSec,
    @Min(1) Integer burst,
    @Min(1) @Max(10) Integer weight,
    @Min(1) @Max(20) Integer maxAttempts
) {}
```

**`dtos/TenantResponse.java`**
```java
public record TenantResponse(
    UUID id,
    String name,
    String slug,
    TenantStatus status,
    int rateLimitPerSec,
    int burst,
    int weight,
    int maxAttempts,
    Instant createdAt,
    Instant updatedAt
) {}
```

### Tenant Admin User DTOs

**`dtos/CreateTenantAdminRequest.java`**
```java
public record CreateTenantAdminRequest(
    @NotBlank @Size(min = 3, max = 100) String username,
    @NotBlank @Size(min = 8, max = 100) String password
) {}
```

**`dtos/TenantAdminResponse.java`**
```java
public record TenantAdminResponse(
    UUID id,
    String username,
    Role role,
    UUID tenantId,
    Instant createdAt
) {}
```

### Global Limit DTOs

**`dtos/GlobalChannelLimitResponse.java`**
```java
public record GlobalChannelLimitResponse(
    Channel channel,
    int ratePerSec,
    int burst
) {}
```

**`dtos/UpdateGlobalLimitRequest.java`**
```java
public record UpdateGlobalLimitRequest(
    @Min(1) int ratePerSec,
    @Min(1) int burst
) {
    @AssertTrue(message = "burst must be >= ratePerSec")
    boolean isBurstValid() {
        return burst >= ratePerSec;
    }
}
```

### Platform Settings DTOs

**`dtos/PlatformSettingsResponse.java`**
```java
public record PlatformSettingsResponse(
    int maxTenantRatePerSec,
    int defaultMaxAttempts
) {}
```

**`dtos/UpdatePlatformSettingsRequest.java`**
```java
public record UpdatePlatformSettingsRequest(
    @Min(1) Integer maxTenantRatePerSec,
    @Min(1) @Max(20) Integer defaultMaxAttempts
) {}
```

---

## 2. Repositories

Check if these already exist. If not, create or extend them.

### TenantRepository (extend if exists)

**`repositories/TenantRepository.java`**

Make sure these methods exist:
```java
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsByName(String name);
    Page<Tenant> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

### AppUserRepository (extend if exists)

**`repositories/AppUserRepository.java`**

Make sure these methods exist:
```java
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    List<AppUser> findByTenantIdAndRole(UUID tenantId, Role role);
}
```

### GlobalChannelLimitRepository

**`repositories/GlobalChannelLimitRepository.java`**

Check if it exists. The entity's PK is the channel enum stored as `VARCHAR`. Depending
on how the entity was set up in H0-2, the ID type may be `String` or `Channel`:

```java
public interface GlobalChannelLimitRepository extends JpaRepository<GlobalChannelLimit, String> {
    // PK = channel name as String (e.g. "EMAIL")
    // findById("EMAIL") works
}
```

If the entity uses `Channel` enum as `@Id`, the repository type should match.
Adjust as needed based on what exists.

### PlatformSettingRepository

**`repositories/PlatformSettingRepository.java`**

```java
public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
    // PK = key string (e.g. "max_tenant_rate_per_sec")
}
```

---

## 3. TenantService

**Location:** `services/TenantService.java`

```java
@Service
@Transactional(readOnly = true)
public class TenantService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final ChannelConfigRepository channelConfigRepository;
    private final PlatformSettingRepository settingRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    // constructor injection

    /**
     * Create a new tenant with all 4 channel configs auto-seeded.
     */
    @Transactional
    public Tenant create(CreateTenantRequest request) {
        // Validate uniqueness
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new ConflictException("Tenant with slug '" + request.slug() + "' already exists");
        }
        if (tenantRepository.existsByName(request.name())) {
            throw new ConflictException("Tenant with name '" + request.name() + "' already exists");
        }

        // Validate rate limit against platform cap
        int maxRate = getMaxTenantRatePerSec();
        if (request.rateLimitPerSec() > maxRate) {
            throw new IllegalArgumentException(
                "rateLimitPerSec (" + request.rateLimitPerSec() +
                ") exceeds platform max (" + maxRate + ")");
        }

        // Validate burst >= rateLimitPerSec
        if (request.burst() < request.rateLimitPerSec()) {
            throw new IllegalArgumentException("burst must be >= rateLimitPerSec");
        }

        Instant now = clock.instant();

        Tenant tenant = new Tenant();
        tenant.setName(request.name());
        tenant.setSlug(request.slug());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setRateLimitPerSec(request.rateLimitPerSec());
        tenant.setBurst(request.burst());
        tenant.setWeight(request.weight());
        tenant.setMaxAttempts(request.maxAttempts());
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);

        tenant = tenantRepository.save(tenant);

        // Auto-seed channel configs: all 4 channels, enabled by default
        for (Channel ch : Channel.values()) {
            ChannelConfig cc = new ChannelConfig();
            cc.setTenant(tenant);
            cc.setChannel(ch);
            cc.setEnabled(true);
            cc.setSettings("{}");
            channelConfigRepository.save(cc);
        }

        return tenant;
    }

    /**
     * Update tenant limits/weight. Partial update — only non-null fields are changed.
     */
    @Transactional
    public Tenant update(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));

        if (request.name() != null) {
            if (!request.name().equals(tenant.getName()) && tenantRepository.existsByName(request.name())) {
                throw new ConflictException("Tenant with name '" + request.name() + "' already exists");
            }
            tenant.setName(request.name());
        }

        if (request.rateLimitPerSec() != null) {
            int maxRate = getMaxTenantRatePerSec();
            if (request.rateLimitPerSec() > maxRate) {
                throw new IllegalArgumentException(
                    "rateLimitPerSec (" + request.rateLimitPerSec() +
                    ") exceeds platform max (" + maxRate + ")");
            }
            tenant.setRateLimitPerSec(request.rateLimitPerSec());
        }

        if (request.burst() != null) {
            tenant.setBurst(request.burst());
        }

        // Validate burst >= rateLimitPerSec after both might have changed
        if (tenant.getBurst() < tenant.getRateLimitPerSec()) {
            throw new IllegalArgumentException("burst must be >= rateLimitPerSec");
        }

        if (request.weight() != null) {
            tenant.setWeight(request.weight());
        }

        if (request.maxAttempts() != null) {
            tenant.setMaxAttempts(request.maxAttempts());
        }

        tenant.setUpdatedAt(clock.instant());

        // If RateLimiterRegistry exists, refresh the tenant's bucket
        // rateLimiterRegistry.refreshTenant(tenantId);

        return tenantRepository.save(tenant);
    }

    /**
     * Suspend a tenant. Suspended tenants:
     * - Cannot submit notifications (API key auth rejects)
     * - Queued notifications are NOT dispatched
     */
    @Transactional
    public Tenant suspend(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));

        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            throw new ConflictException("Tenant is already suspended");
        }

        tenant.setStatus(TenantStatus.SUSPENDED);
        tenant.setUpdatedAt(clock.instant());
        return tenantRepository.save(tenant);
    }

    /**
     * Activate a suspended tenant.
     */
    @Transactional
    public Tenant activate(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));

        if (tenant.getStatus() == TenantStatus.ACTIVE) {
            throw new ConflictException("Tenant is already active");
        }

        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setUpdatedAt(clock.instant());
        return tenantRepository.save(tenant);
    }

    /**
     * Create an admin user for a tenant.
     */
    @Transactional
    public AppUser createTenantAdmin(UUID tenantId, CreateTenantAdminRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));

        if (appUserRepository.existsByUsername(request.username())) {
            throw new ConflictException("Username '" + request.username() + "' already exists");
        }

        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.TENANT_ADMIN);
        user.setTenant(tenant);
        user.setCreatedAt(clock.instant());

        return appUserRepository.save(user);
    }

    /**
     * Get a single tenant by ID.
     */
    public Tenant getById(UUID tenantId) {
        return tenantRepository.findById(tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Tenant", tenantId));
    }

    /**
     * List all tenants, paginated.
     */
    public Page<Tenant> listAll(Pageable pageable) {
        return tenantRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * List admin users for a tenant.
     */
    public List<AppUser> listTenantAdmins(UUID tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new EntityNotFoundException("Tenant", tenantId);
        }
        return appUserRepository.findByTenantIdAndRole(tenantId, Role.TENANT_ADMIN);
    }

    private int getMaxTenantRatePerSec() {
        return settingRepository.findById("max_tenant_rate_per_sec")
            .map(s -> Integer.parseInt(s.getValue()))
            .orElse(1000);
    }
}
```

---

## 4. GlobalLimitService

**Location:** `services/GlobalLimitService.java`

```java
@Service
@Transactional(readOnly = true)
public class GlobalLimitService {

    private final GlobalChannelLimitRepository limitRepository;
    // private final RateLimiterRegistry rateLimiterRegistry;  // if exists, refresh on update

    // constructor injection

    public List<GlobalChannelLimit> listAll() {
        return limitRepository.findAll();
    }

    public GlobalChannelLimit getByChannel(Channel channel) {
        return limitRepository.findById(channel.name())
            .orElseThrow(() -> new EntityNotFoundException("GlobalChannelLimit", channel.name()));
    }

    @Transactional
    public GlobalChannelLimit update(Channel channel, UpdateGlobalLimitRequest request) {
        GlobalChannelLimit limit = limitRepository.findById(channel.name())
            .orElseThrow(() -> new EntityNotFoundException("GlobalChannelLimit", channel.name()));

        limit.setRatePerSec(request.ratePerSec());
        limit.setBurst(request.burst());

        // Refresh the in-memory token bucket if RateLimiterRegistry exists
        // rateLimiterRegistry.refreshChannel(channel);

        return limitRepository.save(limit);
    }
}
```

---

## 5. PlatformSettingsService

**Location:** `services/PlatformSettingsService.java`

```java
@Service
@Transactional(readOnly = true)
public class PlatformSettingsService {

    private final PlatformSettingRepository settingRepository;

    // constructor injection

    public PlatformSettingsResponse getSettings() {
        int maxRate = getInt("max_tenant_rate_per_sec", 1000);
        int defaultAttempts = getInt("default_max_attempts", 5);
        return new PlatformSettingsResponse(maxRate, defaultAttempts);
    }

    @Transactional
    public PlatformSettingsResponse updateSettings(UpdatePlatformSettingsRequest request) {
        if (request.maxTenantRatePerSec() != null) {
            upsert("max_tenant_rate_per_sec", String.valueOf(request.maxTenantRatePerSec()));
        }
        if (request.defaultMaxAttempts() != null) {
            upsert("default_max_attempts", String.valueOf(request.defaultMaxAttempts()));
        }
        return getSettings();
    }

    private int getInt(String key, int defaultValue) {
        return settingRepository.findById(key)
            .map(s -> Integer.parseInt(s.getValue()))
            .orElse(defaultValue);
    }

    private void upsert(String key, String value) {
        PlatformSetting setting = settingRepository.findById(key)
            .orElseGet(() -> {
                PlatformSetting s = new PlatformSetting();
                s.setKey(key);
                return s;
            });
        setting.setValue(value);
        settingRepository.save(setting);
    }
}
```

---

## 6. PlatformAdminController

**Location:** `controllers/PlatformAdminController.java`

**Base path:** `/api/v1/admin`
**Auth:** HTTP Basic, role = `PLATFORM_ADMIN`

```java
@RestController
@RequestMapping("/api/v1/admin")
public class PlatformAdminController {

    private final TenantService tenantService;
    private final GlobalLimitService globalLimitService;
    private final PlatformSettingsService settingsService;

    // constructor injection

    // ──── Tenant CRUD ────

    @PostMapping("/tenants")
    public ResponseEntity<TenantResponse> createTenant(
            @Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(tenant));
    }

    @GetMapping("/tenants")
    public Page<TenantResponse> listTenants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        return tenantService.listAll(PageRequest.of(page, size)).map(this::toResponse);
    }

    @GetMapping("/tenants/{id}")
    public TenantResponse getTenant(@PathVariable UUID id) {
        return toResponse(tenantService.getById(id));
    }

    @PatchMapping("/tenants/{id}")
    public TenantResponse updateTenant(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        return toResponse(tenantService.update(id, request));
    }

    // ──── Tenant lifecycle ────

    @PostMapping("/tenants/{id}/suspend")
    public TenantResponse suspendTenant(@PathVariable UUID id) {
        return toResponse(tenantService.suspend(id));
    }

    @PostMapping("/tenants/{id}/activate")
    public TenantResponse activateTenant(@PathVariable UUID id) {
        return toResponse(tenantService.activate(id));
    }

    // ──── Tenant admin users ────

    @PostMapping("/tenants/{id}/admins")
    public ResponseEntity<TenantAdminResponse> createTenantAdmin(
            @PathVariable UUID id,
            @Valid @RequestBody CreateTenantAdminRequest request) {
        AppUser user = tenantService.createTenantAdmin(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toAdminResponse(user));
    }

    @GetMapping("/tenants/{id}/admins")
    public List<TenantAdminResponse> listTenantAdmins(@PathVariable UUID id) {
        return tenantService.listTenantAdmins(id).stream()
            .map(this::toAdminResponse)
            .toList();
    }

    // ──── Global channel limits ────

    @GetMapping("/global-limits")
    public List<GlobalChannelLimitResponse> listGlobalLimits() {
        return globalLimitService.listAll().stream()
            .map(this::toLimitResponse)
            .toList();
    }

    @PutMapping("/global-limits/{channel}")
    public GlobalChannelLimitResponse updateGlobalLimit(
            @PathVariable String channel,
            @Valid @RequestBody UpdateGlobalLimitRequest request) {
        Channel ch = Channel.valueOf(channel.toUpperCase());
        return toLimitResponse(globalLimitService.update(ch, request));
    }

    // ──── Platform settings ────

    @GetMapping("/settings")
    public PlatformSettingsResponse getSettings() {
        return settingsService.getSettings();
    }

    @PutMapping("/settings")
    public PlatformSettingsResponse updateSettings(
            @Valid @RequestBody UpdatePlatformSettingsRequest request) {
        return settingsService.updateSettings(request);
    }

    // ──── Mappers ────

    private TenantResponse toResponse(Tenant t) {
        return new TenantResponse(
            t.getId(), t.getName(), t.getSlug(), t.getStatus(),
            t.getRateLimitPerSec(), t.getBurst(), t.getWeight(),
            t.getMaxAttempts(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }

    private TenantAdminResponse toAdminResponse(AppUser u) {
        return new TenantAdminResponse(
            u.getId(), u.getUsername(), u.getRole(),
            u.getTenant() != null ? u.getTenant().getId() : null,
            u.getCreatedAt()
        );
    }

    private GlobalChannelLimitResponse toLimitResponse(GlobalChannelLimit l) {
        return new GlobalChannelLimitResponse(
            Channel.valueOf(l.getChannel()), l.getRatePerSec(), l.getBurst()
        );
    }
}
```

**Note on `toLimitResponse`:** The `GlobalChannelLimit` entity might store the channel as a
`String` (the PK column is `VARCHAR`), or as a `Channel` enum with `@Enumerated(STRING)`.
Adjust `Channel.valueOf(l.getChannel())` to match how the entity stores it. If the entity
already returns `Channel`, just use `l.getChannel()` directly.

---

## 7. SecurityConfig verification

Make sure the admin endpoints are secured. This should already exist from H-TEMPLATES,
but verify these lines are present:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/admin/**").hasRole("PLATFORM_ADMIN")
    .requestMatchers("/api/v1/tenant/**").hasRole("TENANT_ADMIN")
    .requestMatchers("/api/v1/notifications/**").authenticated()
    .anyRequest().authenticated()
)
```

If the matchers use a different order or pattern, ensure `/api/v1/admin/**` requires
`PLATFORM_ADMIN` role.

---

## 8. Integration tests

### PlatformAdminTenantApiTest

**Location:** `src/test/java/.../integration/PlatformAdminTenantApiTest.java`

```java
class PlatformAdminTenantApiTest extends BaseIntegrationTest {

    // Auth helper: platform-admin / password123

    @Test void listTenants_returnsSeedTenants() {
        // GET /api/v1/admin/tenants with platform-admin auth
        // Expected: 200, at least 2 tenants (Acme Corp, Globex Inc from seed)
    }

    @Test void getTenant_returnsDetails() {
        // GET /api/v1/admin/tenants/{acmeId}
        // Expected: 200, name=Acme Corp, rateLimitPerSec=100, etc.
    }

    @Test void createTenant_success() {
        // POST /api/v1/admin/tenants with valid body
        // Expected: 201, new UUID, all fields match request
        // Verify: 4 channel configs auto-created (all enabled)
    }

    @Test void createTenant_duplicateSlug_returns409() {
        // POST with slug "acme" (already exists from seed)
        // Expected: 409 Conflict
    }

    @Test void createTenant_duplicateName_returns409() {
        // POST with name "Acme Corp" (already exists)
        // Expected: 409 Conflict
    }

    @Test void createTenant_rateLimitExceedsPlatformMax_returns400() {
        // Platform setting max_tenant_rate_per_sec = 1000
        // POST with rateLimitPerSec = 2000
        // Expected: 400
    }

    @Test void createTenant_burstLessThanRate_returns400() {
        // POST with rateLimitPerSec=100, burst=50
        // Expected: 400
    }

    @Test void createTenant_invalidSlug_returns400() {
        // POST with slug "Invalid Slug!" (uppercase, space, special chars)
        // Expected: 400
    }

    @Test void updateTenant_partialUpdate() {
        // PATCH /api/v1/admin/tenants/{id} with only rateLimitPerSec
        // Expected: 200, rateLimitPerSec changed, other fields unchanged
    }

    @Test void updateTenant_nonexistent_returns404() {
        // PATCH with random UUID
        // Expected: 404
    }
}
```

### TenantLifecycleTest

**Location:** `src/test/java/.../integration/TenantLifecycleTest.java`

```java
class TenantLifecycleTest extends BaseIntegrationTest {

    @Test void suspendTenant_success() {
        // Create tenant → POST /admin/tenants/{id}/suspend
        // Expected: 200, status=SUSPENDED
    }

    @Test void suspendTenant_alreadySuspended_returns409() {
        // Suspend → suspend again
        // Expected: 409
    }

    @Test void activateTenant_success() {
        // Create → suspend → POST /admin/tenants/{id}/activate
        // Expected: 200, status=ACTIVE
    }

    @Test void activateTenant_alreadyActive_returns409() {
        // Active tenant → activate
        // Expected: 409
    }

    @Test void suspendedTenant_cannotSubmitNotifications() {
        // Create tenant → create API key → suspend tenant
        // POST /notifications with that API key
        // Expected: 403 (TenantSuspendedException)
    }
}
```

### TenantAdminUserTest

**Location:** `src/test/java/.../integration/TenantAdminUserTest.java`

```java
class TenantAdminUserTest extends BaseIntegrationTest {

    @Test void createTenantAdmin_success() {
        // POST /api/v1/admin/tenants/{id}/admins
        // Expected: 201, username, role=TENANT_ADMIN, tenantId matches
    }

    @Test void createTenantAdmin_duplicateUsername_returns409() {
        // Create admin "alice" → create again "alice"
        // Expected: 409
    }

    @Test void createdAdmin_canLoginAndAccessTenantAPIs() {
        // Create tenant → create admin → login with new credentials
        // GET /api/v1/tenant/templates → 200 (proves auth works)
    }

    @Test void createdAdmin_cannotAccessOtherTenant() {
        // Create admin for tenant A → try accessing tenant B's templates
        // Expected: empty list or 404 (tenant-scoped isolation)
    }

    @Test void listTenantAdmins_returnsOnlyAdminsForThat Tenant() {
        // GET /api/v1/admin/tenants/{id}/admins
        // Expected: only admins for that tenant, not other tenants' admins
    }

    @Test void createAdmin_forNonexistentTenant_returns404() {
        // POST with random tenant UUID
        // Expected: 404
    }
}
```

### GlobalLimitsTest

**Location:** `src/test/java/.../integration/GlobalLimitsTest.java`

```java
class GlobalLimitsTest extends BaseIntegrationTest {

    @Test void listGlobalLimits_returnsSeedData() {
        // GET /api/v1/admin/global-limits
        // Expected: 200, 4 channels with seed values
    }

    @Test void updateGlobalLimit_success() {
        // PUT /api/v1/admin/global-limits/EMAIL with new values
        // Expected: 200, values updated
    }

    @Test void updateGlobalLimit_burstLessThanRate_returns400() {
        // PUT with ratePerSec=100, burst=50
        // Expected: 400
    }

    @Test void updateGlobalLimit_invalidChannel_returns400() {
        // PUT /api/v1/admin/global-limits/INVALID
        // Expected: 400 (IllegalArgumentException from Channel.valueOf)
    }
}
```

### PlatformSettingsTest

**Location:** `src/test/java/.../integration/PlatformSettingsTest.java`

```java
class PlatformSettingsTest extends BaseIntegrationTest {

    @Test void getSettings_returnsSeedValues() {
        // GET /api/v1/admin/settings
        // Expected: 200, maxTenantRatePerSec=1000, defaultMaxAttempts=5
    }

    @Test void updateSettings_partialUpdate() {
        // PUT with only maxTenantRatePerSec=500
        // Expected: 200, maxTenantRatePerSec=500, defaultMaxAttempts unchanged
    }
}
```

### RBAC enforcement (extend existing tests or add new)

```java
class PlatformAdminRbacTest extends BaseIntegrationTest {

    @Test void tenantAdmin_cannotAccessAdminEndpoints() {
        // acme-admin → GET /api/v1/admin/tenants → 403
    }

    @Test void apiKey_cannotAccessAdminEndpoints() {
        // X-API-Key → GET /api/v1/admin/tenants → 401 or 403
    }

    @Test void platformAdmin_cannotAccessTenantEndpoints() {
        // platform-admin → GET /api/v1/tenant/templates → 403
    }

    @Test void noAuth_returns401() {
        // No credentials → GET /api/v1/admin/tenants → 401
    }
}
```

---

## 9. MANUAL-TEST.md Phase 1 now works

After this block, Phase 1 (1.1, 1.2, 1.3) from MANUAL-TEST.md will work:

```
GET  {{baseUrl}}/api/v1/admin/tenants                        → 200, seed tenants
GET  {{baseUrl}}/api/v1/admin/tenants/10000000-...-000001     → 200, Acme details
POST {{baseUrl}}/api/v1/admin/tenants                        → 201, new tenant
```

Plus the new endpoints you can now test:

```
PATCH {{baseUrl}}/api/v1/admin/tenants/{id}                   → 200, updated
POST  {{baseUrl}}/api/v1/admin/tenants/{id}/suspend           → 200, SUSPENDED
POST  {{baseUrl}}/api/v1/admin/tenants/{id}/activate          → 200, ACTIVE
POST  {{baseUrl}}/api/v1/admin/tenants/{id}/admins            → 201, new admin
GET   {{baseUrl}}/api/v1/admin/tenants/{id}/admins            → 200, admin list
GET   {{baseUrl}}/api/v1/admin/global-limits                  → 200, 4 channels
PUT   {{baseUrl}}/api/v1/admin/global-limits/EMAIL            → 200, updated
GET   {{baseUrl}}/api/v1/admin/settings                       → 200, settings
PUT   {{baseUrl}}/api/v1/admin/settings                       → 200, updated
```

---

## 10. Checklist before commit

- [ ] `./gradlew test` passes — all unit + integration tests green
- [ ] POST /admin/tenants creates tenant with 4 auto-seeded channel configs
- [ ] Duplicate slug / name → 409
- [ ] Rate limit exceeds platform max → 400
- [ ] burst < rateLimitPerSec → 400
- [ ] PATCH partial update works (only specified fields change)
- [ ] Suspend → status=SUSPENDED, activate → status=ACTIVE
- [ ] Double suspend / double activate → 409
- [ ] Suspended tenant cannot submit notifications → 403
- [ ] Create tenant admin → can login and access their tenant's data
- [ ] Created admin cannot see other tenant's data
- [ ] Duplicate username → 409
- [ ] Global limits: list returns seed data, update changes values
- [ ] Platform settings: get returns defaults, update changes values
- [ ] RBAC: tenant admin → admin endpoints = 403
- [ ] RBAC: platform admin → tenant endpoints = 403
- [ ] RBAC: no auth → 401
- [ ] No `Instant.now()` — all use injected Clock
- [ ] All classes in correct flat package

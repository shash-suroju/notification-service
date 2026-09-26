# H-TEMPLATES — Templates + Channel Config Implementation Spec

> Security + tenants + RBAC must be in place before this block (tenant CRUD, HTTP Basic auth,
> API key filter, CurrentTenant resolver). If not, implement them first per CLAUDE.md rules.

---

## What this block delivers

After this block, these features work end-to-end with tests passing:

1. **Template CRUD** — create, list, get, update (new version), deactivate, preview
2. **TemplateRenderer** — `{{variable}}` substitution with HTML escaping for email, SMS length check
3. **Channel config** — enable/disable channels per tenant, channel settings (jsonb)
4. **API key management** — issue (raw key shown once), list, revoke
5. **Full RBAC enforcement** — tenant admin sees only their own data, platform admin has separate scope
6. **Unit tests** — TemplateRenderer, RequestHasher
7. **Integration tests** — template CRUD, channel config, API key lifecycle, RBAC isolation

Commit message at the end:
```
feat: templates, channel config, API keys

- TemplateService with versioning (edit creates new version)
- TemplateRenderer: {{var}} substitution, HTML escape, SMS length
- ChannelConfigService: enable/disable, tenant-scoped
- ApiKeyService: issue (shown once), list, revoke, prefix-based lookup
- Template preview endpoint (render without sending)
- RBAC isolation tests: tenant A cannot see tenant B's data
- Unit tests: renderer edge cases, hash stability
```

---

## 1. TemplateRenderer (pure logic, no Spring)

**Location:** `com.assignment.notificationservice.template.service.TemplateRenderer`

This is the core rendering engine. It is a pure function — no database, no Spring context,
easily unit-tested.

### Behavior

```java
public class TemplateRenderer {

    // Regex: matches {{variableName}} where variableName is [a-zA-Z0-9_]+
    private static final Pattern VARIABLE_PATTERN =
        Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /**
     * Extract all variable names from a template string.
     * "Hello {{name}}, order {{orderId}}" → Set.of("name", "orderId")
     */
    public Set<String> extractVariables(String templateText) {
        if (templateText == null) return Set.of();
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = VARIABLE_PATTERN.matcher(templateText);
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }

    /**
     * Render a template with the given variables.
     *
     * @param templateText  the template string with {{var}} placeholders
     * @param variables     key-value map of variable values
     * @param channel       determines escaping rules
     * @return rendered string
     * @throws MissingVariableException if a required variable is not provided
     */
    public String render(String templateText, Map<String, String> variables, Channel channel) {
        if (templateText == null) return null;

        Matcher m = VARIABLE_PATTERN.matcher(templateText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String value = variables.get(varName);
            if (value == null) {
                throw new MissingVariableException(varName);
            }
            // HTML-escape for EMAIL channel, raw for everything else
            String replacement = (channel == Channel.EMAIL)
                ? escapeHtml(value)
                : Matcher.quoteReplacement(value);
            if (channel != Channel.EMAIL) {
                replacement = Matcher.quoteReplacement(value);
            } else {
                replacement = Matcher.quoteReplacement(escapeHtml(value));
            }
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Validate rendered SMS body length.
     * Standard SMS limit: 480 chars (3 concatenated segments).
     */
    public void validateSmsLength(String renderedBody) {
        if (renderedBody != null && renderedBody.length() > 480) {
            throw new SmsBodyTooLongException(renderedBody.length(), 480);
        }
    }

    private String escapeHtml(String input) {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
```

### Custom exceptions

**`template/exception/MissingVariableException.java`**
```java
public class MissingVariableException extends RuntimeException {
    private final String variableName;

    public MissingVariableException(String variableName) {
        super("Missing required template variable: " + variableName);
        this.variableName = variableName;
    }

    public String getVariableName() { return variableName; }
}
```

**`template/exception/SmsBodyTooLongException.java`**
```java
public class SmsBodyTooLongException extends RuntimeException {
    public SmsBodyTooLongException(int actual, int max) {
        super("SMS body too long: " + actual + " chars (max " + max + ")");
    }
}
```

Add these to the `GlobalExceptionHandler`:
- `MissingVariableException` → 400 Bad Request
- `SmsBodyTooLongException` → 400 Bad Request

---

## 2. TemplateService

**Location:** `com.assignment.notificationservice.template.service.TemplateService`

### Repository

**`template/repository/TemplateRepository.java`**
```java
public interface TemplateRepository extends JpaRepository<Template, UUID> {

    // List all templates for a tenant (latest version of each code+channel pair)
    @Query("""
        SELECT t FROM Template t
        WHERE t.tenant.id = :tenantId
        AND t.version = (
            SELECT MAX(t2.version) FROM Template t2
            WHERE t2.tenant.id = t.tenant.id
            AND t2.code = t.code AND t2.channel = t.channel
        )
        ORDER BY t.code, t.channel
        """)
    Page<Template> findLatestByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    // Find specific template by ID, scoped to tenant
    Optional<Template> findByIdAndTenantId(UUID id, UUID tenantId);

    // Find latest active version for a (tenant, code, channel) — used at ingestion time
    @Query("""
        SELECT t FROM Template t
        WHERE t.tenant.id = :tenantId
        AND t.code = :code AND t.channel = :channel
        AND t.active = true
        ORDER BY t.version DESC
        LIMIT 1
        """)
    Optional<Template> findLatestActive(
        @Param("tenantId") UUID tenantId,
        @Param("code") String code,
        @Param("channel") Channel channel
    );

    // Get max version number for a (tenant, code, channel) — for auto-incrementing
    @Query("""
        SELECT COALESCE(MAX(t.version), 0) FROM Template t
        WHERE t.tenant.id = :tenantId
        AND t.code = :code AND t.channel = :channel
        """)
    int findMaxVersion(
        @Param("tenantId") UUID tenantId,
        @Param("code") String code,
        @Param("channel") Channel channel
    );

    // Find all versions of a specific template code+channel — for version history
    List<Template> findByTenantIdAndCodeAndChannelOrderByVersionDesc(
        UUID tenantId, String code, Channel channel
    );
}
```

### Service methods

```java
@Service
@Transactional(readOnly = true)
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final TemplateRenderer renderer;
    private final Clock clock;

    // Constructor injection (no @Autowired)

    /**
     * Create a new template. If a template with the same (code, channel) already exists
     * for this tenant, this creates version 1 of a new code or fails if v1 already exists.
     */
    @Transactional
    public Template create(UUID tenantId, CreateTemplateRequest request) {
        // 1. Validate: subject required for EMAIL, optional for others
        validateSubjectForChannel(request.channel(), request.subject());

        // 2. Check if version 1 already exists for this (tenant, code, channel)
        int maxVersion = templateRepository.findMaxVersion(tenantId, request.code(), request.channel());
        if (maxVersion > 0) {
            throw new ConflictException(
                "Template '" + request.code() + "' for channel " + request.channel() +
                " already exists. Use PUT to create a new version.");
        }

        // 3. Extract and validate variables from body and subject
        Set<String> bodyVars = renderer.extractVariables(request.body());
        Set<String> subjectVars = renderer.extractVariables(request.subject());
        // (Store for documentation, but don't enforce at creation — enforce at render time)

        // 4. Create entity
        Template template = new Template();
        template.setTenant(/* resolve from tenantId */);
        template.setCode(request.code());
        template.setChannel(request.channel());
        template.setVersion(1);
        template.setSubject(request.subject());
        template.setBody(request.body());
        template.setActive(true);
        template.setCreatedAt(clock.instant());

        return templateRepository.save(template);
    }

    /**
     * Update a template — creates a NEW VERSION. The old version is deactivated.
     * This ensures queued notifications using the old version are not affected.
     */
    @Transactional
    public Template update(UUID tenantId, UUID templateId, UpdateTemplateRequest request) {
        // 1. Find existing template
        Template existing = templateRepository.findByIdAndTenantId(templateId, tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Template", templateId));

        // 2. Deactivate the current version
        existing.setActive(false);
        templateRepository.save(existing);

        // 3. Create new version
        int nextVersion = templateRepository.findMaxVersion(
            tenantId, existing.getCode(), existing.getChannel()) + 1;

        Template newVersion = new Template();
        newVersion.setTenant(existing.getTenant());
        newVersion.setCode(existing.getCode());
        newVersion.setChannel(existing.getChannel());
        newVersion.setVersion(nextVersion);
        newVersion.setSubject(request.subject() != null ? request.subject() : existing.getSubject());
        newVersion.setBody(request.body() != null ? request.body() : existing.getBody());
        newVersion.setActive(true);
        newVersion.setCreatedAt(clock.instant());

        return templateRepository.save(newVersion);
    }

    /**
     * Deactivate a template. It remains in the DB for audit but cannot be used for new sends.
     */
    @Transactional
    public void deactivate(UUID tenantId, UUID templateId) {
        Template template = templateRepository.findByIdAndTenantId(templateId, tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Template", templateId));
        template.setActive(false);
        templateRepository.save(template);
    }

    /**
     * Preview: render a template with sample variables without sending.
     * Returns the rendered subject and body.
     */
    public TemplatePreviewResponse preview(UUID tenantId, UUID templateId,
                                            Map<String, String> sampleVariables) {
        Template template = templateRepository.findByIdAndTenantId(templateId, tenantId)
            .orElseThrow(() -> new EntityNotFoundException("Template", templateId));

        String renderedSubject = renderer.render(template.getSubject(), sampleVariables, template.getChannel());
        String renderedBody = renderer.render(template.getBody(), sampleVariables, template.getChannel());

        if (template.getChannel() == Channel.SMS) {
            renderer.validateSmsLength(renderedBody);
        }

        return new TemplatePreviewResponse(
            template.getId(),
            template.getCode(),
            template.getChannel(),
            template.getVersion(),
            renderedSubject,
            renderedBody,
            renderer.extractVariables(template.getBody()),
            renderer.extractVariables(template.getSubject())
        );
    }

    // get, list — straightforward findByIdAndTenantId / findLatestByTenantId

    private void validateSubjectForChannel(Channel channel, String subject) {
        if (channel == Channel.EMAIL && (subject == null || subject.isBlank())) {
            throw new IllegalArgumentException("Subject is required for EMAIL templates");
        }
    }
}
```

### DTOs (all Java records)

**`template/dto/CreateTemplateRequest.java`**
```java
public record CreateTemplateRequest(
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9_]+$") String code,
    @NotNull Channel channel,
    @Size(max = 500) String subject,            // required for EMAIL, optional otherwise
    @NotBlank @Size(max = 10000) String body
) {}
```

**`template/dto/UpdateTemplateRequest.java`**
```java
public record UpdateTemplateRequest(
    @Size(max = 500) String subject,
    @Size(max = 10000) String body
) {
    // At least one must be provided
    @AssertTrue(message = "At least one of subject or body must be provided")
    boolean isValid() {
        return subject != null || body != null;
    }
}
```

**`template/dto/TemplateResponse.java`**
```java
public record TemplateResponse(
    UUID id,
    String code,
    Channel channel,
    int version,
    String subject,
    String body,
    boolean active,
    Set<String> requiredVariables,     // extracted from body + subject
    Instant createdAt
) {}
```

**`template/dto/TemplatePreviewRequest.java`**
```java
public record TemplatePreviewRequest(
    @NotNull Map<String, String> variables
) {}
```

**`template/dto/TemplatePreviewResponse.java`**
```java
public record TemplatePreviewResponse(
    UUID templateId,
    String code,
    Channel channel,
    int version,
    String renderedSubject,
    String renderedBody,
    Set<String> bodyVariables,
    Set<String> subjectVariables
) {}
```

---

## 3. TemplateController

**Location:** `com.assignment.notificationservice.template.controller.TemplateController`

**Base path:** `/api/v1/tenant/templates`
**Auth:** HTTP Basic, role = `TENANT_ADMIN`
**Tenant resolution:** always from `CurrentTenant.resolve()`, never from path/body

```
POST   /api/v1/tenant/templates                    → 201 Created + TemplateResponse
GET    /api/v1/tenant/templates                     → 200 OK + Page<TemplateResponse>
GET    /api/v1/tenant/templates/{id}                → 200 OK + TemplateResponse
PUT    /api/v1/tenant/templates/{id}                → 200 OK + TemplateResponse (new version)
DELETE /api/v1/tenant/templates/{id}                → 204 No Content (deactivate)
POST   /api/v1/tenant/templates/{id}/preview        → 200 OK + TemplatePreviewResponse
GET    /api/v1/tenant/templates/{id}/versions        → 200 OK + List<TemplateResponse>
```

**Controller implementation rules:**
- Inject `TemplateService`, never the repository directly
- Resolve tenant: `UUID tenantId = CurrentTenant.resolve();`
- Paginated list: `@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size`
  - Enforce max size: `size = Math.min(size, 100);`
- On create, return `ResponseEntity.status(HttpStatus.CREATED).body(response)`
- On delete, return `ResponseEntity.noContent().build()`
- Validate `@Valid` on request body

**Response mapping:**
Build a private helper `toResponse(Template t)` that maps entity → DTO, including extracting
variables using the renderer:
```java
private TemplateResponse toResponse(Template t) {
    Set<String> bodyVars = renderer.extractVariables(t.getBody());
    Set<String> subjectVars = renderer.extractVariables(t.getSubject());
    Set<String> allVars = new LinkedHashSet<>();
    allVars.addAll(subjectVars);
    allVars.addAll(bodyVars);
    return new TemplateResponse(
        t.getId(), t.getCode(), t.getChannel(), t.getVersion(),
        t.getSubject(), t.getBody(), t.isActive(), allVars, t.getCreatedAt()
    );
}
```

---

## 4. ChannelConfigService

**Location:** `com.assignment.notificationservice.channel.service.ChannelConfigService`

### Repository

**`channel/repository/ChannelConfigRepository.java`**
```java
public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, UUID> {
    List<ChannelConfig> findByTenantId(UUID tenantId);
    Optional<ChannelConfig> findByTenantIdAndChannel(UUID tenantId, Channel channel);
}
```

### Service

```java
@Service
@Transactional(readOnly = true)
public class ChannelConfigService {

    private final ChannelConfigRepository channelConfigRepository;
    private final TenantRepository tenantRepository;

    /**
     * Get all channel configs for a tenant.
     * If a channel has no config row yet, return a default (disabled) entry.
     */
    public List<ChannelConfigResponse> getAllForTenant(UUID tenantId) {
        List<ChannelConfig> existing = channelConfigRepository.findByTenantId(tenantId);
        Map<Channel, ChannelConfig> byChannel = existing.stream()
            .collect(Collectors.toMap(ChannelConfig::getChannel, c -> c));

        // Return all 4 channels, filling in defaults for unconfigured ones
        return Arrays.stream(Channel.values())
            .map(ch -> {
                ChannelConfig cc = byChannel.get(ch);
                if (cc != null) {
                    return toResponse(cc);
                }
                return new ChannelConfigResponse(null, ch, false, Map.of());
            })
            .toList();
    }

    /**
     * Enable or disable a channel for a tenant, and optionally update settings.
     * Creates the config row if it doesn't exist yet (upsert).
     */
    @Transactional
    public ChannelConfigResponse upsert(UUID tenantId, Channel channel,
                                         UpdateChannelConfigRequest request) {
        ChannelConfig config = channelConfigRepository
            .findByTenantIdAndChannel(tenantId, channel)
            .orElseGet(() -> {
                ChannelConfig cc = new ChannelConfig();
                cc.setTenant(tenantRepository.getReferenceById(tenantId));
                cc.setChannel(channel);
                return cc;
            });

        config.setEnabled(request.enabled());
        if (request.settings() != null) {
            // Serialize map to JSON string for the jsonb column
            config.setSettings(serializeSettings(request.settings()));
        }

        return toResponse(channelConfigRepository.save(config));
    }

    /**
     * Check if a channel is enabled for a tenant. Used by ingestion service.
     */
    public boolean isChannelEnabled(UUID tenantId, Channel channel) {
        return channelConfigRepository.findByTenantIdAndChannel(tenantId, channel)
            .map(ChannelConfig::isEnabled)
            .orElse(false);
    }
}
```

### DTOs

**`channel/dto/ChannelConfigResponse.java`**
```java
public record ChannelConfigResponse(
    UUID id,                          // null if default/unconfigured
    Channel channel,
    boolean enabled,
    Map<String, String> settings
) {}
```

**`channel/dto/UpdateChannelConfigRequest.java`**
```java
public record UpdateChannelConfigRequest(
    @NotNull Boolean enabled,
    Map<String, String> settings      // optional: fromAddress, senderId, etc.
) {}
```

### Controller

**Location:** `com.assignment.notificationservice.channel.controller.ChannelController`

**Base path:** `/api/v1/tenant/channels`
**Auth:** HTTP Basic, role = `TENANT_ADMIN`

```
GET    /api/v1/tenant/channels                      → 200 OK + List<ChannelConfigResponse>
PUT    /api/v1/tenant/channels/{channel}             → 200 OK + ChannelConfigResponse
```

The `{channel}` path variable is the enum name: `EMAIL`, `SMS`, `PUSH`, `IN_APP`.
Use a case-insensitive converter or handle in the controller:
```java
@PutMapping("/{channel}")
public ChannelConfigResponse update(
        @PathVariable String channel,
        @Valid @RequestBody UpdateChannelConfigRequest request) {
    Channel ch = Channel.valueOf(channel.toUpperCase());
    UUID tenantId = CurrentTenant.resolve();
    return channelConfigService.upsert(tenantId, ch, request);
}
```

---

## 5. ApiKeyService

**Location:** `com.assignment.notificationservice.apikey.service.ApiKeyService`

### Key format

Full key: `ntfy_{prefix}_{random}`
- Prefix: 8 random alphanumeric chars (used for DB lookup)
- Random: 32 random alphanumeric chars (the secret part)
- Total: `ntfy_` + 8 + `_` + 32 = 46 chars
- Storage: SHA-256 hash of the full key
- Lookup: `SELECT * FROM api_key WHERE prefix = ? AND status = 'ACTIVE'`
- Verify: `SHA256(providedKey) == row.keyHash`

### Repository

**`apikey/repository/ApiKeyRepository.java`**
```java
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<ApiKey> findByPrefixAndStatus(String prefix, ApiKeyStatus status);
}
```

### Service

```java
@Service
@Transactional(readOnly = true)
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    private static final String KEY_PREFIX = "ntfy_";
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * Issue a new API key. Returns the full key ONCE — it is never stored or retrievable.
     */
    @Transactional
    public ApiKeyCreateResponse create(UUID tenantId, String name) {
        String prefix = randomString(8);
        String secret = randomString(32);
        String fullKey = KEY_PREFIX + prefix + "_" + secret;
        String hash = sha256(fullKey);

        ApiKey apiKey = new ApiKey();
        apiKey.setTenant(/* resolve tenant reference */);
        apiKey.setPrefix(prefix);
        apiKey.setKeyHash(hash);
        apiKey.setName(name);
        apiKey.setStatus(ApiKeyStatus.ACTIVE);
        apiKey.setCreatedAt(clock.instant());

        apiKeyRepository.save(apiKey);

        // Return the raw key — this is the ONLY time it's available
        return new ApiKeyCreateResponse(apiKey.getId(), prefix, fullKey, name, apiKey.getCreatedAt());
    }

    /**
     * Revoke an API key. Soft delete — the row stays for audit.
     */
    @Transactional
    public void revoke(UUID tenantId, UUID keyId) {
        ApiKey key = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
            .orElseThrow(() -> new EntityNotFoundException("ApiKey", keyId));
        key.setStatus(ApiKeyStatus.REVOKED);
        apiKeyRepository.save(key);
    }

    /**
     * Authenticate an API key. Used by ApiKeyAuthFilter.
     * Returns the tenant ID if valid, empty if invalid.
     */
    @Transactional  // updates last_used_at
    public Optional<ApiKeyAuthentication> authenticate(String rawKey) {
        // 1. Parse: must start with "ntfy_" and have 3 parts
        if (!rawKey.startsWith(KEY_PREFIX)) return Optional.empty();
        String[] parts = rawKey.substring(KEY_PREFIX.length()).split("_", 2);
        if (parts.length != 2) return Optional.empty();
        String prefix = parts[0];

        // 2. Lookup by prefix
        Optional<ApiKey> found = apiKeyRepository.findByPrefixAndStatus(prefix, ApiKeyStatus.ACTIVE);
        if (found.isEmpty()) return Optional.empty();

        ApiKey apiKey = found.get();

        // 3. Verify hash
        String hash = sha256(rawKey);
        if (!hash.equals(apiKey.getKeyHash())) return Optional.empty();

        // 4. Update last_used_at
        apiKey.setLastUsedAt(clock.instant());
        apiKeyRepository.save(apiKey);

        // 5. Check tenant is active
        if (apiKey.getTenant().getStatus() != TenantStatus.ACTIVE) {
            return Optional.empty();  // suspended tenant
        }

        return Optional.of(new ApiKeyAuthentication(apiKey.getTenant().getId(), apiKey.getId()));
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(secureRandom.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);  // SHA-256 is always available
        }
    }
}
```

### DTOs

**`apikey/dto/CreateApiKeyRequest.java`**
```java
public record CreateApiKeyRequest(
    @Size(max = 100) String name
) {}
```

**`apikey/dto/ApiKeyCreateResponse.java`**
```java
public record ApiKeyCreateResponse(
    UUID id,
    String prefix,
    String rawKey,          // ⚠️ shown ONCE, never retrievable again
    String name,
    Instant createdAt
) {}
```

**`apikey/dto/ApiKeyResponse.java`** (for list — does NOT include raw key)
```java
public record ApiKeyResponse(
    UUID id,
    String prefix,
    String name,
    ApiKeyStatus status,
    Instant createdAt,
    Instant lastUsedAt
) {}
```

**`apikey/dto/ApiKeyAuthentication.java`** (internal, not exposed via API)
```java
public record ApiKeyAuthentication(
    UUID tenantId,
    UUID apiKeyId
) {}
```

### Controller

**Location:** `com.assignment.notificationservice.apikey.controller.ApiKeyController`

**Base path:** `/api/v1/tenant/api-keys`
**Auth:** HTTP Basic, role = `TENANT_ADMIN`

```
POST   /api/v1/tenant/api-keys                     → 201 Created + ApiKeyCreateResponse
GET    /api/v1/tenant/api-keys                     → 200 OK + List<ApiKeyResponse>
DELETE /api/v1/tenant/api-keys/{id}                → 204 No Content (revoke)
```

---

## 6. ApiKeyAuthFilter

**Location:** `com.assignment.notificationservice.security.ApiKeyAuthFilter`

This filter intercepts requests with `X-API-Key` header and authenticates them
via `ApiKeyService.authenticate()`.

```java
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");

        if (apiKey != null && !apiKey.isBlank()) {
            Optional<ApiKeyAuthentication> auth = apiKeyService.authenticate(apiKey);
            if (auth.isPresent()) {
                // Set authentication in SecurityContext
                TenantPrincipal principal = new TenantPrincipal(
                    auth.get().tenantId(),
                    null,                    // no username for API key auth
                    Role.TENANT_ADMIN,       // API keys act as tenant scope
                    AuthMethod.API_KEY
                );
                UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(token);
            }
            // If auth failed, don't set context — let security chain handle 401
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only filter paths that accept API key auth
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/notifications");
    }
}
```

### TenantPrincipal

**`security/TenantPrincipal.java`**
```java
public class TenantPrincipal implements UserDetails {
    private final UUID tenantId;
    private final String username;       // null for API key auth
    private final Role role;
    private final AuthMethod authMethod; // BASIC or API_KEY

    // Implement UserDetails methods:
    // getAuthorities() → List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
    // getPassword() → null (already authenticated)
    // getUsername() → username or "api-key:" + tenantId
    // isAccountNonExpired(), isAccountNonLocked(), isCredentialsNonExpired(), isEnabled() → true
}

public enum AuthMethod { BASIC, API_KEY }
```

### CurrentTenant

**`security/CurrentTenant.java`**
```java
public final class CurrentTenant {
    private CurrentTenant() {}

    /**
     * Resolve the tenant ID from the current security context.
     * @throws AccessDeniedException if no tenant is associated (e.g., platform admin calling tenant API)
     */
    public static UUID resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TenantPrincipal principal)) {
            throw new AccessDeniedException("No authenticated tenant");
        }
        UUID tenantId = principal.getTenantId();
        if (tenantId == null) {
            throw new AccessDeniedException("Platform admin cannot access tenant-scoped resources");
        }
        return tenantId;
    }
}
```

---

## 7. SecurityConfig update (RBAC enforcement)

Update the existing permit-all SecurityConfig to enforce roles:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AppUserDetailsService userDetailsService;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Platform admin endpoints
                .requestMatchers("/api/v1/admin/**").hasRole("PLATFORM_ADMIN")
                // Tenant admin endpoints
                .requestMatchers("/api/v1/tenant/**").hasRole("TENANT_ADMIN")
                // Send API (API key auth)
                .requestMatchers("/api/v1/notifications/**").authenticated()
                // Health/actuator (if any)
                .requestMatchers("/actuator/**").permitAll()
                // Everything else
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }
}
```

### AppUserDetailsService

**`security/AppUserDetailsService.java`**
```java
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = appUserRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return new TenantPrincipal(
            user.getTenant() != null ? user.getTenant().getId() : null,
            user.getUsername(),
            user.getRole(),
            AuthMethod.BASIC
        );
    }
}
```

**Note:** `TenantPrincipal` must also carry the password hash for Basic auth.
Either add `passwordHash` to `TenantPrincipal` or use Spring's `User.builder()` wrapping.
Choose whichever is cleaner — the key requirement is that `CurrentTenant.resolve()`
returns the right tenant ID from both Basic and API key auth paths.

---

## 8. RequestHasher (for notification idempotency — build now, use later)

**Location:** `com.assignment.notificationservice.notification.ingest.RequestHasher`

Build this now because it's a pure utility and we need it tested.

```java
public class RequestHasher {

    /**
     * Compute a stable SHA-256 hash of the notification request payload.
     * The hash is independent of JSON key ordering in variables.
     *
     * Canonical form: channel|recipient|templateCode|key1=val1,key2=val2,...
     * (variables sorted by key)
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

## 9. Unit tests

### TemplateRendererTest

**Location:** `src/test/java/.../unit/TemplateRendererTest.java`

NO Spring context. Pure JUnit 5.

```java
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    // --- Variable extraction ---

    @Test void extractVariables_findsAllVariables() {
        // "Hello {{name}}, order {{orderId}} shipped" → {"name", "orderId"}
    }

    @Test void extractVariables_handlesSpacesInsideBraces() {
        // "Hello {{ name }}" → {"name"}
    }

    @Test void extractVariables_emptyString() {
        // "" → empty set
    }

    @Test void extractVariables_nullString() {
        // null → empty set
    }

    @Test void extractVariables_noVariables() {
        // "Plain text with no variables" → empty set
    }

    @Test void extractVariables_duplicateVariables() {
        // "{{name}} and {{name}}" → {"name"} (deduplicated)
    }

    // --- Rendering ---

    @Test void render_substitutesAllVariables() {
        // "Hello {{name}}, order {{orderId}}" + {name: "Alice", orderId: "123"}
        // → "Hello Alice, order 123"
    }

    @Test void render_throwsOnMissingVariable() {
        // "Hello {{name}}" + {} → MissingVariableException("name")
    }

    @Test void render_ignoresExtraVariables() {
        // "Hello {{name}}" + {name: "Alice", extra: "ignored"} → "Hello Alice"
    }

    @Test void render_htmlEscapesForEmail() {
        // "Hello {{name}}" + {name: "<script>alert('xss')</script>"} + EMAIL
        // → "Hello &lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"
    }

    @Test void render_noEscapingForSms() {
        // "Hello {{name}}" + {name: "<b>Alice</b>"} + SMS
        // → "Hello <b>Alice</b>" (raw, no escaping)
    }

    @Test void render_noEscapingForPush() {
        // Same as SMS — raw output
    }

    @Test void render_nullTemplateReturnsNull() {
        // render(null, {}, EMAIL) → null
    }

    @Test void render_handlesSpecialRegexChars() {
        // Variables containing $ and \ should not break regex replacement
        // "Price: {{amount}}" + {amount: "$100.00"} → "Price: $100.00"
    }

    // --- SMS length ---

    @Test void validateSmsLength_passesUnderLimit() {
        // 480 char string → no exception
    }

    @Test void validateSmsLength_failsOverLimit() {
        // 481 char string → SmsBodyTooLongException
    }

    @Test void validateSmsLength_exactLimit() {
        // 480 char string → no exception
    }
}
```

### RequestHasherTest

**Location:** `src/test/java/.../unit/RequestHasherTest.java`

```java
class RequestHasherTest {

    @Test void hash_stableAcrossKeyOrder() {
        // {a: "1", b: "2"} and {b: "2", a: "1"} produce the same hash
        Map<String, String> vars1 = new LinkedHashMap<>();
        vars1.put("a", "1"); vars1.put("b", "2");
        Map<String, String> vars2 = new LinkedHashMap<>();
        vars2.put("b", "2"); vars2.put("a", "1");

        String h1 = RequestHasher.hash("EMAIL", "test@example.com", "welcome", vars1);
        String h2 = RequestHasher.hash("EMAIL", "test@example.com", "welcome", vars2);
        assertThat(h1).isEqualTo(h2);
    }

    @Test void hash_differentPayloadsDifferentHash() {
        // Different recipient → different hash
    }

    @Test void hash_nullVariables() {
        // null variables map → valid hash
    }

    @Test void hash_emptyVariables() {
        // empty map → valid hash, same as null
    }

    @Test void hash_is64CharHexString() {
        // SHA-256 output is 64 hex chars
    }
}
```

---

## 10. Integration tests

### TemplateApiTest

**Location:** `src/test/java/.../integration/TemplateApiTest.java`

Extends `BaseIntegrationTest`. Uses HTTP calls via `TestRestTemplate`.

```java
class TemplateApiTest extends BaseIntegrationTest {

    // Setup: create a tenant + tenant admin user (or use seed data)

    @Test void createTemplate_success() {
        // POST /api/v1/tenant/templates with valid body → 201
        // Response has: id, code, channel, version=1, body, active=true
    }

    @Test void createTemplate_duplicateCodeAndChannel_returns409() {
        // Create template with code="welcome", channel=EMAIL
        // Create again with same code+channel → 409 Conflict
    }

    @Test void createTemplate_emailWithoutSubject_returns400() {
        // POST with channel=EMAIL but no subject → 400
    }

    @Test void createTemplate_smsWithoutSubject_succeeds() {
        // POST with channel=SMS and no subject → 201 (subject optional for SMS)
    }

    @Test void createTemplate_invalidCode_returns400() {
        // code with uppercase or special chars → 400
    }

    @Test void getTemplate_returnsCorrectData() {
        // Create → GET by ID → matches
    }

    @Test void getTemplate_otherTenantId_returns404() {
        // Create template as tenant A → GET as tenant B → 404
    }

    @Test void listTemplates_paginatedAndFiltered() {
        // Create 3 templates → GET list → returns all 3, paginated
    }

    @Test void updateTemplate_createsNewVersion() {
        // Create v1 → PUT with new body → response has version=2
        // GET v1 → active=false
        // GET v2 → active=true
    }

    @Test void deleteTemplate_deactivates() {
        // Create → DELETE → GET → active=false
    }

    @Test void previewTemplate_rendersWithVariables() {
        // Create template with {{name}} → POST preview with {name: "Alice"}
        // → 200 with rendered body containing "Alice"
    }

    @Test void previewTemplate_missingVariable_returns400() {
        // Template has {{name}} → preview with {} → 400
    }

    @Test void previewTemplate_smsLengthExceeded_returns400() {
        // Create SMS template → preview with very long variable value → 400
    }

    @Test void getVersionHistory_returnsAllVersions() {
        // Create v1 → update → update → GET /versions → [v3, v2, v1] descending
    }
}
```

### ChannelConfigApiTest

**Location:** `src/test/java/.../integration/ChannelConfigApiTest.java`

```java
class ChannelConfigApiTest extends BaseIntegrationTest {

    @Test void listChannels_returnsAllFourChannels() {
        // GET /api/v1/tenant/channels → 200 with 4 entries
    }

    @Test void enableChannel_createsConfigIfNotExists() {
        // PUT /api/v1/tenant/channels/PUSH {enabled: true} → 200
        // GET → PUSH is enabled
    }

    @Test void disableChannel() {
        // Enable EMAIL → disable EMAIL → GET → EMAIL disabled
    }

    @Test void updateChannelSettings() {
        // PUT with settings: {fromAddress: "noreply@acme.com"} → stored in jsonb
    }

    @Test void channelConfig_otherTenant_isolated() {
        // Tenant A enables PUSH → tenant B's PUSH is still disabled/unconfigured
    }
}
```

### ApiKeyApiTest

**Location:** `src/test/java/.../integration/ApiKeyApiTest.java`

```java
class ApiKeyApiTest extends BaseIntegrationTest {

    @Test void createApiKey_returnsRawKeyOnce() {
        // POST /api/v1/tenant/api-keys → 201
        // Response contains rawKey starting with "ntfy_"
    }

    @Test void listApiKeys_doesNotExposeRawKey() {
        // Create key → GET list → entries have prefix but NOT rawKey
    }

    @Test void revokeApiKey_softDeletes() {
        // Create → DELETE → list → status = REVOKED
    }

    @Test void revokedKeyCannotAuthenticate() {
        // Create key → revoke → use key in X-API-Key header → 401
    }

    @Test void validKeyAuthenticates() {
        // Create key → use in X-API-Key header on /api/v1/notifications → not 401
        // (actual notification endpoint doesn't exist yet, but auth should pass)
    }

    @Test void apiKeyUpdatesLastUsedAt() {
        // Use key → list → lastUsedAt is set
    }
}
```

### RbacIsolationTest

**Location:** `src/test/java/.../integration/RbacIsolationTest.java`

```java
class RbacIsolationTest extends BaseIntegrationTest {

    // Setup: two tenants (A and B) with their own admins

    @Test void tenantAdminA_cannotSeeTenantB_templates() {
        // Create template as A → GET as B → 404
    }

    @Test void tenantAdminA_cannotSeeTenantB_channels() {
        // Update channel as A → list as B → B's channels unaffected
    }

    @Test void tenantAdminA_cannotSeeTenantB_apiKeys() {
        // Create key as A → list as B → empty (or only B's keys)
    }

    @Test void tenantAdmin_cannotAccessPlatformAdminEndpoints() {
        // Tenant admin → GET /api/v1/admin/tenants → 403
    }

    @Test void platformAdmin_cannotAccessTenantEndpoints() {
        // Platform admin → GET /api/v1/tenant/templates → 403
    }

    @Test void unauthenticated_returns401() {
        // No credentials → any protected endpoint → 401
    }
}
```

---

## 11. GlobalExceptionHandler additions

Add these mappings to the existing handler:

```java
@ExceptionHandler(MissingVariableException.class)
public ProblemDetail handleMissingVariable(MissingVariableException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        ex.getMessage()
    );
    pd.setTitle("Missing Template Variable");
    pd.setProperty("variableName", ex.getVariableName());
    return pd;
}

@ExceptionHandler(SmsBodyTooLongException.class)
public ProblemDetail handleSmsBodyTooLong(SmsBodyTooLongException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
}

@ExceptionHandler(MethodArgumentNotValidException.class)
public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setTitle("Validation Failed");
    Map<String, String> errors = new LinkedHashMap<>();
    ex.getBindingResult().getFieldErrors()
        .forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
    pd.setProperty("fieldErrors", errors);
    pd.setDetail("Validation failed for " + errors.size() + " field(s)");
    return pd;
}
```

---

## 12. Seed data fix

The V099 seed migration has `PLACEHOLDER_HASH_REPLACE_AT_STARTUP` for the API key.
Replace it with the actual SHA-256 hash of `ntfy_acme1234_testkey12345678901234567890ab`:

Compute the hash and hardcode it in the migration. Or better: pick a known test key,
compute its SHA-256, and document it in the README:

```
Test API Key (Acme Corp): ntfy_acme1234_testkey12345678901234567890ab
SHA-256: <computed value>
```

Use Java to compute:
```java
MessageDigest.getInstance("SHA-256")
    .digest("ntfy_acme1234_testkey12345678901234567890ab".getBytes(UTF_8))
```
→ hex-encode the result and paste into V099.

---

## Checklist before commit

- [ ] `./gradlew test` passes — all unit + integration tests green
- [ ] Template CRUD works: create, list, get, update (new version), deactivate
- [ ] Template preview renders variables correctly
- [ ] HTML escaping works for EMAIL channel
- [ ] SMS length validation works
- [ ] Channel config: list all 4, enable/disable, update settings
- [ ] API keys: issue (raw key in response), list (no raw key), revoke
- [ ] API key auth works on `/api/v1/notifications/**` path
- [ ] RBAC: tenant A ≠ tenant B, tenant admin ≠ platform admin, unauth = 401
- [ ] ProblemDetail errors for all validation failures
- [ ] No `Instant.now()` anywhere — all use injected Clock

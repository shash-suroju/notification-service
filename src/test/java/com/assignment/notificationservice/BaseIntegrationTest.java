package com.assignment.notificationservice;

import com.assignment.notificationservice.constants.NotificationConstants;
import com.assignment.notificationservice.constants.SecurityConstants;
import com.assignment.notificationservice.models.AppUser;
import com.assignment.notificationservice.models.Tenant;
import com.assignment.notificationservice.models.enums.Role;
import com.assignment.notificationservice.repositories.AppUserRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import com.assignment.notificationservice.support.MutableClock;
import com.assignment.notificationservice.support.TestClockConfig;
import com.assignment.notificationservice.support.TestSender;
import com.assignment.notificationservice.support.TestSenderConfig;
import com.assignment.notificationservice.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for every integration test: real PostgreSQL via Testcontainers, real Flyway
 * migrations, and a {@link MutableClock} in place of the system clock.
 *
 * <p>H2 is not an option — the dispatcher depends on {@code FOR UPDATE SKIP LOCKED},
 * partial indexes and {@code jsonb}, none of which H2 reproduces faithfully.
 *
 * <p>The container is a JVM-wide singleton, started once and reaped by Testcontainers at
 * exit. A per-class {@code @Container} would be stopped after the first test class while
 * Spring's cached context kept pointing at its (now dead) port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestClockConfig.class, TestSenderConfig.class})
public abstract class BaseIntegrationTest {

    /** Password for every user created by {@link #setupTenant}. */
    protected static final String TEST_PASSWORD = "test-password";

    /** Seeded by V099; all seeded users share the password {@code password123}. */
    protected static final String SEED_PASSWORD = "password123";
    protected static final String SEED_PLATFORM_ADMIN = "platform-admin";

    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("notify_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        PG.start();
    }

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

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Creates a fresh tenant with its own TENANT_ADMIN user. The slug is randomised so tests
     * sharing the singleton database never collide.
     */
    protected TestTenant setupTenant(String label) {
        String slug = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        String username = slug + "-admin";
        return new TransactionTemplate(transactionManager).execute(status -> {
            Tenant tenant = tenantRepository.save(new Tenant("Tenant " + slug, slug, clock.instant()));
            appUserRepository.save(new AppUser(
                    username, passwordEncoder.encode(TEST_PASSWORD), Role.TENANT_ADMIN, tenant, clock.instant()));
            return new TestTenant(tenant.getId(), slug, username, TEST_PASSWORD);
        });
    }

    /** A TestRestTemplate authenticated as the tenant's admin via HTTP Basic. */
    protected TestRestTemplate as(TestTenant tenant) {
        return restTemplate.withBasicAuth(tenant.username(), tenant.password());
    }

    protected TestRestTemplate asPlatformAdmin() {
        return restTemplate.withBasicAuth(SEED_PLATFORM_ADMIN, SEED_PASSWORD);
    }

    // ---- sending fixtures ----

    /** EMAIL template created by {@link #setupSender}. Needs {@code name} and {@code companyName}. */
    protected static final String WELCOME_TEMPLATE = "welcome";
    /** SMS template created by {@link #setupSender}. Needs {@code code}. */
    protected static final String OTP_TEMPLATE = "otp";

    /**
     * A fresh tenant configured through the real admin APIs — channels enabled, templates
     * created, API key issued — so sending tests start from a state a real tenant could reach.
     */
    protected TestSender setupSender(String label) {
        TestTenant tenant = setupTenant(label);
        enableChannel(tenant, "EMAIL");
        enableChannel(tenant, "SMS");
        createTemplate(tenant, Map.of(
                "code", WELCOME_TEMPLATE,
                "channel", "EMAIL",
                "subject", "Welcome to {{companyName}}",
                "body", "Hello {{name}}, welcome to {{companyName}}!"));
        createTemplate(tenant, Map.of(
                "code", OTP_TEMPLATE,
                "channel", "SMS",
                "body", "Your code is {{code}}"));
        JsonNode key = as(tenant).postForObject("/api/v1/tenant/api-keys", Map.of("name", "test"), JsonNode.class);
        return new TestSender(tenant, key.get("rawKey").asText());
    }

    protected void enableChannel(TestTenant tenant, String channel) {
        ResponseEntity<String> res = as(tenant).exchange("/api/v1/tenant/channels/" + channel, HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true)), String.class);
        assertThat(res.getStatusCode()).as("enable " + channel).isEqualTo(HttpStatus.OK);
    }

    protected String createTemplate(TestTenant tenant, Map<String, Object> body) {
        ResponseEntity<JsonNode> res = as(tenant).postForEntity("/api/v1/tenant/templates", body, JsonNode.class);
        assertThat(res.getStatusCode()).as("create template " + body.get("code")).isEqualTo(HttpStatus.CREATED);
        return res.getBody().get("id").asText();
    }

    /** A valid welcome-email payload; override fields by copying the map. */
    protected static Map<String, Object> welcomeEmail(String recipient) {
        Map<String, Object> body = new HashMap<>();
        body.put("channel", "EMAIL");
        body.put("recipient", recipient);
        body.put("templateCode", WELCOME_TEMPLATE);
        body.put("variables", Map.of("name", "Alice", "companyName", "Acme"));
        return body;
    }

    /** POST /api/v1/notifications with only the API key (plus Idempotency-Key when non-null). */
    protected ResponseEntity<JsonNode> send(TestSender sender, String idempotencyKey, Object body) {
        return restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, apiKeyHeaders(sender.apiKey(), idempotencyKey)), JsonNode.class);
    }

    protected static HttpHeaders apiKeyHeaders(String apiKey, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set(SecurityConstants.API_KEY_HEADER, apiKey);
        }
        if (idempotencyKey != null) {
            headers.set(NotificationConstants.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return headers;
    }

    protected static String newIdempotencyKey() {
        return "key-" + UUID.randomUUID();
    }
}

package com.assignment.notificationservice;

import com.assignment.notificationservice.models.AppUser;
import com.assignment.notificationservice.models.Tenant;
import com.assignment.notificationservice.models.enums.Role;
import com.assignment.notificationservice.repositories.AppUserRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import com.assignment.notificationservice.support.MutableClock;
import com.assignment.notificationservice.support.TestClockConfig;
import com.assignment.notificationservice.support.TestTenant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

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
@Import(TestClockConfig.class)
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
}

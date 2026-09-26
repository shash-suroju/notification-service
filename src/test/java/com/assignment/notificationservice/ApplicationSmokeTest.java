package com.assignment.notificationservice;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.assignment.notificationservice.channel.entity.ChannelConfig;
import com.assignment.notificationservice.common.Channel;
import com.assignment.notificationservice.tenant.entity.Tenant;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the H0-2 skeleton is wired: the context starts against real PostgreSQL, all
 * migrations applied, the clock is swappable, and the entity mappings actually match
 * the Flyway schema (Hibernate runs with {@code ddl-auto: validate}).
 */
class ApplicationSmokeTest extends BaseIntegrationTest {

    private static final UUID GLOBEX_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        assertThat(context.getBean(javax.sql.DataSource.class)).isNotNull();

        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        // V001..V011 plus the V099 dev seed
        assertThat(versions).containsExactly(
                "001", "002", "003", "004", "005", "006",
                "007", "008", "009", "010", "011", "099");
    }

    @Test
    void everyTableFromTheSchemaExists() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "tenant", "app_user", "api_key", "global_channel_limit", "platform_setting",
                "channel_config", "template", "notification", "delivery_attempt",
                "notification_event", "in_app_message");
    }

    @Test
    void seedDataLoads() {
        List<String> slugs = jdbcTemplate.queryForList(
                "SELECT slug FROM tenant ORDER BY slug", String.class);
        assertThat(slugs).containsExactly("acme", "globex");

        Integer admins = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE role = 'PLATFORM_ADMIN'", Integer.class);
        assertThat(admins).isEqualTo(1);

        Integer channelLimits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM global_channel_limit", Integer.class);
        assertThat(channelLimits).isEqualTo(4);

        Integer templates = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM template", Integer.class);
        assertThat(templates).isEqualTo(4);
    }

    /**
     * Guards the one mapping that silently breaks at runtime rather than at startup:
     * a String field bound to a {@code jsonb} column. Schema validation passes either way,
     * but without the JSON JDBC type the INSERT fails with a type mismatch.
     */
    @Test
    void jsonbColumnsRoundTripThroughJpa() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        UUID configId = tx.execute(status -> {
            Tenant globex = entityManager.getReference(Tenant.class, GLOBEX_ID);
            // Globex is seeded with EMAIL, SMS and IN_APP, so PUSH is free to claim here.
            ChannelConfig config = new ChannelConfig(globex, Channel.PUSH, true);
            config.setSettings("{\"provider\":\"fcm\",\"ttlSeconds\":300}");
            entityManager.persist(config);
            entityManager.flush();
            return config.getId();
        });

        String settings = tx.execute(status ->
                entityManager.find(ChannelConfig.class, configId).getSettings());

        assertThat(settings).contains("fcm").contains("300");
    }
}

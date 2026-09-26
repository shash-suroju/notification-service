package com.assignment.notificationservice;

import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.ChannelWorkerPools;
import com.assignment.notificationservice.services.DispatchScheduler;
import com.assignment.notificationservice.services.FairTenantSelector;
import com.assignment.notificationservice.services.RateLimiterRegistry;
import com.assignment.notificationservice.support.ProgrammableSender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Base for tests that drive the dispatcher.
 *
 * <p>The database and the dispatcher's in-memory state (token buckets, selector cursor,
 * programmed senders) are shared by every test in the JVM, and a real {@code tick()} dispatches
 * <em>all</em> due work — including rows other test classes left behind. So each test starts
 * from an empty queue and fresh buckets.
 */
public abstract class BaseDispatcherTest extends BaseIntegrationTest {

    protected static final Duration AWAIT = Duration.ofSeconds(15);

    @Autowired
    protected DispatchScheduler scheduler;

    @Autowired
    protected ChannelWorkerPools workerPools;

    @Autowired
    protected RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    protected FairTenantSelector fairTenantSelector;

    @Autowired
    @Qualifier("emailSender")
    protected ProgrammableSender emailSender;

    @Autowired
    @Qualifier("smsSender")
    protected ProgrammableSender smsSender;

    @Autowired
    @Qualifier("pushSender")
    protected ProgrammableSender pushSender;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDispatcherState() {
        await().atMost(AWAIT).until(workerPools::isIdle);
        jdbc.update("DELETE FROM notification");   // cascades to attempts, events, in-app messages
        emailSender.reset();
        smsSender.reset();
        pushSender.reset();
        rateLimiterRegistry.resetAll();
        fairTenantSelector.resetCursor();
    }

    /** Runs one tick and waits until every notification it claimed has a recorded outcome. */
    protected int tickAndAwait() {
        int dispatched = scheduler.tick();
        awaitNoInFlight();
        return dispatched;
    }

    protected void awaitNoInFlight() {
        await().atMost(AWAIT).until(() -> countWithStatus("PROCESSING") == 0 && workerPools.isIdle());
    }

    /**
     * Inserts due PENDING rows directly — bypassing the API keeps large fixtures (thousands of
     * rows) fast. Ingestion itself is covered by the ingestion tests.
     */
    protected List<UUID> insertPending(UUID tenantId, Channel channel, int count) {
        Instant now = clock.instant();
        List<UUID> ids = new ArrayList<>(count);
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(new Object[]{id, tenantId, channel.name(), "user" + i + "@example.com",
                    "fixture-" + id, "body " + i, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)});
        }
        jdbc.batchUpdate("""
                INSERT INTO notification (id, tenant_id, channel, recipient, idempotency_key, request_hash,
                                          body, status, next_attempt_at, max_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'fixture', ?, 'PENDING', ?, 5, ?, ?)
                """, rows);
        return ids;
    }

    protected int countWithStatus(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE status = ?", Integer.class, status);
    }

    protected int countWithStatus(UUID tenantId, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE tenant_id = ? AND status = ?",
                Integer.class, tenantId, status);
    }

    protected String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    protected Instant nextAttemptAt(UUID id) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM notification WHERE id = ?", Timestamp.class, id)
                .toInstant();
    }

    protected UUID idOf(ResponseEntity<JsonNode> res) {
        return UUID.fromString(res.getBody().get("id").asText());
    }
}

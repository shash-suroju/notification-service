package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.support.TestSender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CancelNotificationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("cancel");
    }

    @Test
    void cancel_scheduled_notification_succeeds() {
        String id = submitScheduled();
        clock.advance(Duration.ofMinutes(1));

        ResponseEntity<JsonNode> res = cancel(sender, id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.getBody().get("templateCode").asText()).isEqualTo(WELCOME_TEMPLATE);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");

        List<Map<String, Object>> events = events(id);
        assertThat(events).hasSize(2);
        Map<String, Object> cancelEvent = events.get(1);
        assertThat(cancelEvent.get("from_status")).isEqualTo("SCHEDULED");
        assertThat(cancelEvent.get("to_status")).isEqualTo("CANCELLED");
        assertThat(cancelEvent.get("reason")).isEqualTo("cancelled_by_user");
        assertThat(cancelEvent.get("actor")).isEqualTo("API");
    }

    @Test
    void cancel_pending_notification_succeeds() {
        String id = submitImmediate();

        ResponseEntity<JsonNode> res = cancel(sender, id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(events(id).get(1).get("from_status")).isEqualTo("PENDING");
    }

    @Test
    void cancel_updates_updatedAt_from_the_clock() {
        String id = submitImmediate();
        clock.advance(Duration.ofMinutes(7));

        cancel(sender, id);

        JsonNode detail = restTemplate.exchange("/api/v1/notifications/" + id, HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), JsonNode.class).getBody();
        assertThat(detail.get("updatedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(detail.get("timeline").get(1).get("occurredAt").asText()).isEqualTo(clock.instant().toString());
    }

    @Test
    void cancel_sent_notification_returns409() {
        String id = submitImmediate();
        // The dispatcher is not built yet; move the row to SENT directly.
        jdbcTemplate.update("UPDATE notification SET status = 'SENT' WHERE id = ?", UUID.fromString(id));

        ResponseEntity<JsonNode> res = cancel(sender, id);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("detail").asText()).contains("status SENT");
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(events(id)).hasSize(1);   // no audit row for a rejected change
    }

    @Test
    void cancel_processing_notification_returns409() {
        String id = submitImmediate();
        jdbcTemplate.update("UPDATE notification SET status = 'PROCESSING' WHERE id = ?", UUID.fromString(id));

        assertThat(cancel(sender, id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancel_nonexistent_notification_returns404() {
        assertThat(cancel(sender, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancel_other_tenants_notification_returns404() {
        TestSender other = setupSender("cancel-other");
        String id = submitImmediate();

        assertThat(cancel(other, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void cancel_already_cancelled_returns409() {
        String id = submitImmediate();
        assertThat(cancel(sender, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> again = cancel(sender, id);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(events(id)).hasSize(2);
    }

    // ---- helpers ----

    private String submitImmediate() {
        return send(sender, newIdempotencyKey(), welcomeEmail("a@example.com")).getBody().get("id").asText();
    }

    private String submitScheduled() {
        Map<String, Object> request = welcomeEmail("a@example.com");
        request.put("scheduledAt", clock.instant().plus(Duration.ofHours(2)).toString());
        return send(sender, newIdempotencyKey(), request).getBody().get("id").asText();
    }

    private ResponseEntity<JsonNode> cancel(TestSender as, String id) {
        return restTemplate.exchange("/api/v1/notifications/" + id + "/cancel", HttpMethod.POST,
                new HttpEntity<>(apiKeyHeaders(as.apiKey(), null)), JsonNode.class);
    }

    private String statusOf(String id) {
        return jdbcTemplate.queryForObject("SELECT status FROM notification WHERE id = ?",
                String.class, UUID.fromString(id));
    }

    private List<Map<String, Object>> events(String id) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM notification_event WHERE notification_id = ? ORDER BY occurred_at, seq",
                UUID.fromString(id));
    }
}

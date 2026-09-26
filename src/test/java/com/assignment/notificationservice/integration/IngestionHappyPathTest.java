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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionHappyPathTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("happy");
    }

    @Test
    void submit_immediate_returns202_statusPending() {
        Instant now = clock.instant();

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = res.getBody();
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(body.get("recipient").asText()).isEqualTo("alice@example.com");
        assertThat(body.get("templateCode").asText()).isEqualTo(WELCOME_TEMPLATE);
        assertThat(body.get("templateVersion").asInt()).isEqualTo(1);
        assertThat(body.has("scheduledAt")).isFalse();
        assertThat(body.get("createdAt").asText()).isEqualTo(now.toString());

        UUID id = UUID.fromString(body.get("id").asText());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM notification WHERE id = ?", id);
        assertThat(row.get("tenant_id")).isEqualTo(sender.tenant().id());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isEqualTo(now);
        assertThat(row.get("scheduled_at")).isNull();
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("max_attempts")).isEqualTo(5);   // tenant default
        assertThat(row.get("request_hash")).asString().hasSize(64);

        Map<String, Object> event = jdbcTemplate.queryForMap(
                "SELECT * FROM notification_event WHERE notification_id = ?", id);
        assertThat(event.get("from_status")).isNull();
        assertThat(event.get("to_status")).isEqualTo("PENDING");
        assertThat(event.get("actor")).isEqualTo("API");
        assertThat(event.get("reason")).isEqualTo("immediate_submit");
        assertThat(event.get("tenant_id")).isEqualTo(sender.tenant().id());
    }

    @Test
    void submit_scheduled_returns202_statusScheduled() {
        Instant scheduledAt = clock.instant().plus(Duration.ofHours(1));
        Map<String, Object> request = welcomeEmail("alice@example.com");
        request.put("scheduledAt", scheduledAt.toString());

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody().get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(res.getBody().get("scheduledAt").asText()).isEqualTo(scheduledAt.toString());

        UUID id = UUID.fromString(res.getBody().get("id").asText());
        Instant nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM notification WHERE id = ?", Timestamp.class, id).toInstant();
        assertThat(nextAttemptAt).isEqualTo(scheduledAt);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM notification_event WHERE notification_id = ?", String.class, id))
                .isEqualTo("scheduled_submit");
    }

    @Test
    void submit_rendersTemplateAtAcceptTime() {
        String id = send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com"))
                .getBody().get("id").asText();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT subject, body, variables ->> 'name' AS name FROM notification WHERE id = ?", UUID.fromString(id));
        assertThat(row.get("subject")).isEqualTo("Welcome to Acme");
        assertThat(row.get("body")).isEqualTo("Hello Alice, welcome to Acme!");
        assertThat(row.get("name")).isEqualTo("Alice");   // original variables kept as jsonb
    }

    @Test
    void templateEditAfterSubmit_doesNotChangeTheQueuedSnapshot() {
        String firstId = send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com"))
                .getBody().get("id").asText();

        // Edit the template → version 2
        JsonNode templates = as(sender.tenant()).getForObject("/api/v1/tenant/templates", JsonNode.class);
        String templateId = null;
        for (JsonNode t : templates.get("content")) {
            if (t.get("code").asText().equals(WELCOME_TEMPLATE)) {
                templateId = t.get("id").asText();
            }
        }
        as(sender.tenant()).exchange("/api/v1/tenant/templates/" + templateId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("body", "Hi {{name}} — v2")), JsonNode.class);

        JsonNode first = detail(firstId);
        assertThat(first.get("body").asText()).isEqualTo("Hello Alice, welcome to Acme!");
        assertThat(first.get("templateVersion").asInt()).isEqualTo(1);

        JsonNode second = send(sender, newIdempotencyKey(), welcomeEmail("bob@example.com")).getBody();
        assertThat(second.get("templateVersion").asInt()).isEqualTo(2);
        assertThat(detail(second.get("id").asText()).get("body").asText()).isEqualTo("Hi Alice — v2");
    }

    @Test
    void emailVariablesAreHtmlEscapedInTheSnapshot() {
        Map<String, Object> request = welcomeEmail("alice@example.com");
        request.put("variables", Map.of("name", "<script>x</script>", "companyName", "Acme"));

        String id = send(sender, newIdempotencyKey(), request).getBody().get("id").asText();

        assertThat(detail(id).get("body").asText()).isEqualTo("Hello &lt;script&gt;x&lt;/script&gt;, welcome to Acme!");
    }

    @Test
    void smsSubmit_usesSmsTemplateAndHasNoSubject() {
        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), Map.of(
                "channel", "SMS", "recipient", "+14155551234", "templateCode", OTP_TEMPLATE,
                "variables", Map.of("code", "482913")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode detail = detail(res.getBody().get("id").asText());
        assertThat(detail.get("body").asText()).isEqualTo("Your code is 482913");
        assertThat(detail.has("subject")).isFalse();
    }

    @Test
    void getById_returnsDetailWithTimeline() {
        String id = send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com"))
                .getBody().get("id").asText();

        ResponseEntity<JsonNode> res = restTemplate.exchange("/api/v1/notifications/" + id, HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("subject").asText()).isEqualTo("Welcome to Acme");
        assertThat(body.get("variables").get("companyName").asText()).isEqualTo("Acme");
        assertThat(body.get("attemptCount").asInt()).isZero();
        assertThat(body.get("maxAttempts").asInt()).isEqualTo(5);
        assertThat(body.get("attempts")).isEmpty();
        assertThat(body.get("timeline")).hasSize(1);
        JsonNode created = body.get("timeline").get(0);
        assertThat(created.has("fromStatus")).isFalse();
        assertThat(created.get("toStatus").asText()).isEqualTo("PENDING");
        assertThat(created.get("actor").asText()).isEqualTo("API");
    }

    @Test
    void getById_otherTenantsNotification_returns404() {
        TestSender other = setupSender("happy-other");
        String id = send(sender, newIdempotencyKey(), welcomeEmail("alice@example.com"))
                .getBody().get("id").asText();

        ResponseEntity<JsonNode> res = restTemplate.exchange("/api/v1/notifications/" + id, HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(other.apiKey(), null)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void list_returnsOwnNotificationsNewestFirst_withFilters() {
        send(sender, newIdempotencyKey(), welcomeEmail("first@example.com"));
        clock.advance(Duration.ofSeconds(1));
        send(sender, newIdempotencyKey(), Map.of("channel", "SMS", "recipient", "+14155551234",
                "templateCode", OTP_TEMPLATE, "variables", Map.of("code", "1")));
        clock.advance(Duration.ofSeconds(1));
        send(sender, newIdempotencyKey(), welcomeEmail("third@example.com"));

        JsonNode all = listViaApiKey("");
        assertThat(all.get("totalElements").asInt()).isEqualTo(3);
        assertThat(all.get("content").get(0).get("recipient").asText()).isEqualTo("third@example.com");

        assertThat(listViaApiKey("?channel=SMS").get("totalElements").asInt()).isEqualTo(1);
        assertThat(listViaApiKey("?status=PENDING&channel=EMAIL").get("totalElements").asInt()).isEqualTo(2);
        assertThat(listViaApiKey("?status=SENT").get("totalElements").asInt()).isZero();
    }

    private JsonNode detail(String id) {
        return restTemplate.exchange("/api/v1/notifications/" + id, HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), JsonNode.class).getBody();
    }

    private JsonNode listViaApiKey(String query) {
        return restTemplate.exchange("/api/v1/notifications" + query, HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), JsonNode.class).getBody();
    }
}

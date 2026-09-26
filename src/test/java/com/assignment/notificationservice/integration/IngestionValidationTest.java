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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rejection path of the submit API. Each test also checks the rejected request left
 * no row behind — validation must happen before anything is written.
 */
class IngestionValidationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("valid");
    }

    @Test
    void missing_idempotency_key_header_returns400() {
        ResponseEntity<JsonNode> res = send(sender, null, welcomeEmail("a@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Missing Required Header");
        assertThat(res.getBody().get("detail").asText()).contains("Idempotency-Key");
        assertThat(countNotifications()).isZero();
    }

    @Test
    void blank_idempotency_key_returns400() {
        ResponseEntity<JsonNode> res = send(sender, "   ", welcomeEmail("a@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countNotifications()).isZero();
    }

    @Test
    void overlong_idempotency_key_returns400() {
        ResponseEntity<JsonNode> res = send(sender, "k".repeat(256), welcomeEmail("a@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).contains("at most 255");
    }

    @Test
    void missing_required_fields_returns400_withFieldErrors() {
        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), Map.of());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode fieldErrors = res.getBody().get("fieldErrors");
        assertThat(fieldErrors.has("channel")).isTrue();
        assertThat(fieldErrors.has("recipient")).isTrue();
        assertThat(fieldErrors.has("templateCode")).isTrue();
    }

    @Test
    void disabled_channel_returns400() {
        as(sender.tenant()).exchange("/api/v1/tenant/channels/SMS", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false)), JsonNode.class);

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), Map.of(
                "channel", "SMS", "recipient", "+14155551234", "templateCode", OTP_TEMPLATE,
                "variables", Map.of("code", "1")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Channel Disabled");
        assertThat(res.getBody().get("channel").asText()).isEqualTo("SMS");
        assertThat(countNotifications()).isZero();
    }

    @Test
    void never_configured_channel_returns400() {
        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), Map.of(
                "channel", "PUSH", "recipient", "a".repeat(64), "templateCode", "anything"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Channel Disabled");
    }

    @Test
    void unknown_template_code_returns400() {
        Map<String, Object> request = welcomeEmail("a@example.com");
        request.put("templateCode", "nonexistent");

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Template Not Found");
    }

    @Test
    void template_channel_mismatch_returns400() {
        // "welcome" exists only for EMAIL; SMS is enabled but has no "welcome" template.
        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), Map.of(
                "channel", "SMS", "recipient", "+14155551234", "templateCode", WELCOME_TEMPLATE,
                "variables", Map.of("name", "A", "companyName", "B")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText())
                .isEqualTo("No active template found with code 'welcome' for channel SMS");
    }

    @Test
    void deactivated_template_returns400() {
        JsonNode templates = as(sender.tenant()).getForObject("/api/v1/tenant/templates", JsonNode.class);
        for (JsonNode t : templates.get("content")) {
            if (t.get("code").asText().equals(WELCOME_TEMPLATE)) {
                as(sender.tenant()).exchange("/api/v1/tenant/templates/" + t.get("id").asText(),
                        HttpMethod.DELETE, null, Void.class);
            }
        }

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), welcomeEmail("a@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Template Not Found");
    }

    @Test
    void invalid_email_recipient_returns400() {
        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), welcomeEmail("not-an-email"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Invalid Recipient");
        assertThat(res.getBody().get("channel").asText()).isEqualTo("EMAIL");
        assertThat(res.getBody().toString()).doesNotContain("not-an-email");   // PII not echoed
        assertThat(countNotifications()).isZero();
    }

    @Test
    void invalid_e164_phone_returns400() {
        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), Map.of(
                "channel", "SMS", "recipient", "12345", "templateCode", OTP_TEMPLATE,
                "variables", Map.of("code", "1")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).contains("E.164");
    }

    @Test
    void missing_template_variable_returns400() {
        Map<String, Object> request = welcomeEmail("a@example.com");
        request.put("variables", Map.of("name", "Alice"));   // companyName missing

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("variableName").asText()).isEqualTo("companyName");
        assertThat(countNotifications()).isZero();
    }

    @Test
    void no_variables_at_all_returns400_forTemplateWithPlaceholders() {
        Map<String, Object> request = welcomeEmail("a@example.com");
        request.remove("variables");

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Missing Template Variable");
    }

    @Test
    void sms_body_too_long_returns400() {
        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), Map.of(
                "channel", "SMS", "recipient", "+14155551234", "templateCode", OTP_TEMPLATE,
                "variables", Map.of("code", "9".repeat(500))));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("SMS Body Too Long");
    }

    @Test
    void scheduledAt_in_past_returns400() {
        Map<String, Object> request = welcomeEmail("a@example.com");
        request.put("scheduledAt", clock.instant().minus(Duration.ofHours(1)).toString());

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).isEqualTo("scheduledAt must be in the future");
    }

    @Test
    void scheduledAt_too_far_ahead_returns400() {
        Map<String, Object> request = welcomeEmail("a@example.com");
        request.put("scheduledAt", clock.instant().plus(Duration.ofDays(31)).toString());

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).contains("30 days");
    }

    @Test
    void scheduledAt_exactly_30_days_ahead_is_accepted() {
        Map<String, Object> request = welcomeEmail("a@example.com");
        request.put("scheduledAt", clock.instant().plus(Duration.ofDays(30)).toString());

        assertThat(send(sender, newIdempotencyKey(), request).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void suspended_tenant_returns403() {
        jdbcTemplate.update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", sender.tenant().id());

        ResponseEntity<JsonNode> res = send(sender, newIdempotencyKey(), welcomeEmail("a@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Tenant Suspended");
        assertThat(countNotifications()).isZero();
    }

    @Test
    void invalid_api_key_returns401() {
        ResponseEntity<String> res = restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(welcomeEmail("a@example.com"),
                        apiKeyHeaders("ntfy_bogus123_" + "x".repeat(32), newIdempotencyKey())),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missing_api_key_returns401() {
        ResponseEntity<String> res = restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(welcomeEmail("a@example.com"), apiKeyHeaders(null, newIdempotencyKey())),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private int countNotifications() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE tenant_id = ?", Integer.class, sender.tenant().id());
    }
}

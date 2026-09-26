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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Tenant-admin console view of notifications (HTTP Basic), separate from the API-key send API. */
class TenantNotificationApiTest extends BaseIntegrationTest {

    private static final String BASE = "/api/v1/tenant/notifications";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("console");
    }

    @Test
    void list_showsTheTenantsNotifications_withFiltersAndPaging() {
        for (int i = 0; i < 3; i++) {
            send(sender, newIdempotencyKey(), welcomeEmail("user" + i + "@example.com"));
        }
        send(sender, newIdempotencyKey(), Map.of("channel", "SMS", "recipient", "+14155551234",
                "templateCode", OTP_TEMPLATE, "variables", Map.of("code", "1")));

        JsonNode all = as(sender.tenant()).getForObject(BASE + "?size=2", JsonNode.class);
        assertThat(all.get("totalElements").asInt()).isEqualTo(4);
        assertThat(all.get("content")).hasSize(2);

        assertThat(as(sender.tenant()).getForObject(BASE + "?channel=SMS", JsonNode.class)
                .get("totalElements").asInt()).isEqualTo(1);
        assertThat(as(sender.tenant()).getForObject(BASE + "?size=5000", JsonNode.class)
                .get("size").asInt()).isEqualTo(100);
    }

    @Test
    void detail_includesTimeline() {
        String id = send(sender, newIdempotencyKey(), welcomeEmail("a@example.com")).getBody().get("id").asText();

        JsonNode detail = as(sender.tenant()).getForObject(BASE + "/" + id, JsonNode.class);

        assertThat(detail.get("body").asText()).isEqualTo("Hello Alice, welcome to Acme!");
        assertThat(detail.get("timeline")).hasSize(1);
    }

    @Test
    void adminCancel_isAuditedWithActorAdmin() {
        String id = send(sender, newIdempotencyKey(), welcomeEmail("a@example.com")).getBody().get("id").asText();

        ResponseEntity<JsonNode> res = as(sender.tenant()).postForEntity(BASE + "/" + id + "/cancel", null, JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("CANCELLED");
        String actor = jdbcTemplate.queryForObject(
                "SELECT actor FROM notification_event WHERE notification_id = ? AND to_status = 'CANCELLED'",
                String.class, UUID.fromString(id));
        assertThat(actor).isEqualTo("ADMIN");
    }

    @Test
    void otherTenantsAdmin_gets404() {
        TestSender other = setupSender("console-other");
        String id = send(sender, newIdempotencyKey(), welcomeEmail("a@example.com")).getBody().get("id").asText();

        assertThat(as(other.tenant()).getForEntity(BASE + "/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(other.tenant()).postForEntity(BASE + "/" + id + "/cancel", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(other.tenant()).getForObject(BASE, JsonNode.class).get("totalElements").asInt()).isZero();
    }

    @Test
    void apiKey_cannotUseTheConsoleEndpoints() {
        ResponseEntity<String> res = restTemplate.exchange(BASE, HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void platformAdmin_isForbidden() {
        assertThat(asPlatformAdmin().getForEntity(BASE, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}

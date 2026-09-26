package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantLifecycleTest extends BaseIntegrationTest {

    private static final String TENANTS = "/api/v1/admin/tenants";

    @Test
    void suspendTenant_success() {
        String id = createTenant("suspend");

        ResponseEntity<JsonNode> res = lifecycle(id, "suspend");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(asPlatformAdmin().getForObject(TENANTS + "/" + id, JsonNode.class).get("status").asText())
                .isEqualTo("SUSPENDED");
    }

    @Test
    void suspendTenant_alreadySuspended_returns409() {
        String id = createTenant("suspend-twice");
        lifecycle(id, "suspend");

        ResponseEntity<JsonNode> again = lifecycle(id, "suspend");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("detail").asText()).isEqualTo("Tenant is already suspended");
    }

    @Test
    void activateTenant_success() {
        String id = createTenant("reactivate");
        lifecycle(id, "suspend");

        ResponseEntity<JsonNode> res = lifecycle(id, "activate");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void activateTenant_alreadyActive_returns409() {
        String id = createTenant("active");

        assertThat(lifecycle(id, "activate").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void lifecycle_unknownTenant_returns404() {
        assertThat(lifecycle(UUID.randomUUID().toString(), "suspend").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The whole onboarding path through real APIs only: the platform admin creates a tenant and
     * its admin; the tenant admin creates a template and an API key; the tenant's backend sends.
     * Then suspension blocks sending and reactivation restores it.
     */
    @Test
    void suspendedTenant_cannotSubmitNotifications() {
        String tenantId = createTenant("onboard");
        String username = "admin-" + UUID.randomUUID().toString().substring(0, 8);
        asPlatformAdmin().postForEntity(TENANTS + "/" + tenantId + "/admins",
                Map.of("username", username, "password", "s3cret-pass"), JsonNode.class);
        var tenantAdmin = restTemplate.withBasicAuth(username, "s3cret-pass");

        // Channels were auto-seeded as enabled, so no channel setup is needed.
        tenantAdmin.postForEntity("/api/v1/tenant/templates", Map.of(
                "code", "hello", "channel", "SMS", "body", "Hello {{name}}"), JsonNode.class);
        String apiKey = tenantAdmin.postForObject("/api/v1/tenant/api-keys", Map.of("name", "backend"), JsonNode.class)
                .get("rawKey").asText();
        Map<String, Object> sms = Map.of("channel", "SMS", "recipient", "+14155551234",
                "templateCode", "hello", "variables", Map.of("name", "Ada"));

        assertThat(sendWithKey(apiKey, sms).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        lifecycle(tenantId, "suspend");
        ResponseEntity<JsonNode> blocked = sendWithKey(apiKey, sms);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody().get("title").asText()).isEqualTo("Tenant Suspended");

        lifecycle(tenantId, "activate");
        assertThat(sendWithKey(apiKey, sms).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private String createTenant(String label) {
        String slug = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return asPlatformAdmin().postForObject(TENANTS, Map.of(
                "name", "Tenant " + slug, "slug", slug, "rateLimitPerSec", 10, "burst", 20), JsonNode.class)
                .get("id").asText();
    }

    private ResponseEntity<JsonNode> lifecycle(String id, String action) {
        return asPlatformAdmin().postForEntity(TENANTS + "/" + id + "/" + action, null, JsonNode.class);
    }

    private ResponseEntity<JsonNode> sendWithKey(String apiKey, Map<String, Object> body) {
        return restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(body, apiKeyHeaders(apiKey, newIdempotencyKey())), JsonNode.class);
    }
}

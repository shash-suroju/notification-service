package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAdminTenantApiTest extends BaseIntegrationTest {

    private static final String TENANTS = "/api/v1/admin/tenants";
    private static final String ACME_ID = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listTenants_returnsSeedTenants() {
        String created = createTenant(tenantBody("listed")).getBody().get("slug").asText();

        JsonNode page = asPlatformAdmin().getForObject(TENANTS + "?size=100", JsonNode.class);

        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);
        List<String> slugs = new ArrayList<>();
        page.get("content").forEach(t -> slugs.add(t.get("slug").asText()));
        assertThat(slugs).contains("acme", "globex", created);
    }

    @Test
    void listTenants_isNewestFirst_andPageSizeIsCapped() {
        String older = createTenant(tenantBody("older")).getBody().get("slug").asText();
        clock.advance(Duration.ofSeconds(1));
        String newer = createTenant(tenantBody("newer")).getBody().get("slug").asText();

        JsonNode page = asPlatformAdmin().getForObject(TENANTS + "?size=5000", JsonNode.class);

        assertThat(page.get("size").asInt()).isEqualTo(100);
        List<String> slugs = new ArrayList<>();
        page.get("content").forEach(t -> slugs.add(t.get("slug").asText()));
        assertThat(slugs.indexOf(newer)).isLessThan(slugs.indexOf(older));
    }

    @Test
    void getTenant_returnsDetails() {
        JsonNode acme = asPlatformAdmin().getForObject(TENANTS + "/" + ACME_ID, JsonNode.class);

        assertThat(acme.get("name").asText()).isEqualTo("Acme Corp");
        assertThat(acme.get("slug").asText()).isEqualTo("acme");
        assertThat(acme.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(acme.get("rateLimitPerSec").asInt()).isEqualTo(100);
        assertThat(acme.get("burst").asInt()).isEqualTo(200);
        assertThat(acme.get("weight").asInt()).isEqualTo(1);
        assertThat(acme.get("maxAttempts").asInt()).isEqualTo(5);
    }

    @Test
    void getTenant_unknownId_returns404() {
        assertThat(asPlatformAdmin().getForEntity(TENANTS + "/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createTenant_success() {
        Map<String, Object> body = tenantBody("fresh");
        body.put("rateLimitPerSec", 50);
        body.put("burst", 150);
        body.put("weight", 3);
        body.put("maxAttempts", 7);

        ResponseEntity<JsonNode> res = createTenant(body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode t = res.getBody();
        UUID id = UUID.fromString(t.get("id").asText());
        assertThat(t.get("name").asText()).isEqualTo(body.get("name"));
        assertThat(t.get("slug").asText()).isEqualTo(body.get("slug"));
        assertThat(t.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(t.get("rateLimitPerSec").asInt()).isEqualTo(50);
        assertThat(t.get("burst").asInt()).isEqualTo(150);
        assertThat(t.get("weight").asInt()).isEqualTo(3);
        assertThat(t.get("maxAttempts").asInt()).isEqualTo(7);
        assertThat(t.get("createdAt").asText()).isEqualTo(clock.instant().toString());

        // All four channels are seeded, enabled, with empty settings.
        List<Map<String, Object>> configs = jdbc.queryForList(
                "SELECT channel, enabled, settings::text AS settings FROM channel_config WHERE tenant_id = ? ORDER BY channel", id);
        assertThat(configs).extracting(c -> c.get("channel")).containsExactly("EMAIL", "IN_APP", "PUSH", "SMS");
        assertThat(configs).allSatisfy(c -> {
            assertThat(c.get("enabled")).isEqualTo(true);
            assertThat(c.get("settings")).isEqualTo("{}");
        });
    }

    @Test
    void createTenant_optionalFieldsDefault_toWeight1_andPlatformDefaultMaxAttempts() {
        Map<String, Object> body = tenantBody("defaults");
        body.remove("weight");
        body.remove("maxAttempts");

        JsonNode t = createTenant(body).getBody();

        assertThat(t.get("weight").asInt()).isEqualTo(1);
        assertThat(t.get("maxAttempts").asInt()).isEqualTo(5);   // default_max_attempts seed value
    }

    @Test
    void createTenant_duplicateSlug_returns409() {
        Map<String, Object> body = tenantBody("dup");
        body.put("slug", "acme");

        ResponseEntity<JsonNode> res = createTenant(body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("detail").asText()).contains("slug 'acme'");
    }

    @Test
    void createTenant_duplicateName_returns409() {
        Map<String, Object> body = tenantBody("dup-name");
        body.put("name", "Acme Corp");

        assertThat(createTenant(body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createTenant_rateLimitExceedsPlatformMax_returns400() {
        Map<String, Object> body = tenantBody("too-fast");
        body.put("rateLimitPerSec", 2000);
        body.put("burst", 4000);

        ResponseEntity<JsonNode> res = createTenant(body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).contains("exceeds platform max (1000)");
    }

    @Test
    void createTenant_burstLessThanRate_returns400() {
        Map<String, Object> body = tenantBody("low-burst");
        body.put("rateLimitPerSec", 100);
        body.put("burst", 50);

        ResponseEntity<JsonNode> res = createTenant(body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).isEqualTo("burst must be >= rateLimitPerSec");
    }

    @Test
    void createTenant_invalidSlug_returns400() {
        Map<String, Object> body = tenantBody("bad");
        body.put("slug", "Invalid Slug!");

        ResponseEntity<JsonNode> res = createTenant(body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fieldErrors").get("slug").asText()).contains("lowercase");
    }

    @Test
    void createTenant_missingOrOutOfRangeFields_returns400WithEachField() {
        ResponseEntity<JsonNode> res = createTenant(Map.of("weight", 11, "maxAttempts", 0));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode errors = res.getBody().get("fieldErrors");
        assertThat(errors.has("name")).isTrue();
        assertThat(errors.has("slug")).isTrue();
        assertThat(errors.has("rateLimitPerSec")).isTrue();
        assertThat(errors.has("burst")).isTrue();
        assertThat(errors.has("weight")).isTrue();
        assertThat(errors.has("maxAttempts")).isTrue();
    }

    @Test
    void updateTenant_partialUpdate() {
        JsonNode created = createTenant(tenantBody("patch")).getBody();
        String id = created.get("id").asText();
        clock.advance(Duration.ofMinutes(1));

        ResponseEntity<JsonNode> res = patch(id, Map.of("rateLimitPerSec", 150));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = res.getBody();
        assertThat(updated.get("rateLimitPerSec").asInt()).isEqualTo(150);
        assertThat(updated.get("name").asText()).isEqualTo(created.get("name").asText());
        assertThat(updated.get("burst").asInt()).isEqualTo(created.get("burst").asInt());
        assertThat(updated.get("weight").asInt()).isEqualTo(created.get("weight").asInt());
        assertThat(updated.get("maxAttempts").asInt()).isEqualTo(created.get("maxAttempts").asInt());
        assertThat(updated.get("createdAt")).isEqualTo(created.get("createdAt"));
        assertThat(updated.get("updatedAt").asText()).isEqualTo(clock.instant().toString());
    }

    @Test
    void updateTenant_burstRuleCheckedOnMergedValues() {
        String id = createTenant(tenantBody("merged")).getBody().get("id").asText();   // rate 100, burst 200

        // Raising only the rate above the stored burst is rejected...
        assertThat(patch(id, Map.of("rateLimitPerSec", 300)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // ...but raising both together is fine.
        assertThat(patch(id, Map.of("rateLimitPerSec", 300, "burst", 300)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateTenant_rateAbovePlatformMax_returns400() {
        String id = createTenant(tenantBody("capped")).getBody().get("id").asText();

        assertThat(patch(id, Map.of("rateLimitPerSec", 5000, "burst", 5000)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateTenant_renameToExistingName_returns409() {
        String id = createTenant(tenantBody("rename")).getBody().get("id").asText();

        assertThat(patch(id, Map.of("name", "Globex Inc")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateTenant_invalidWeight_returns400() {
        String id = createTenant(tenantBody("weight")).getBody().get("id").asText();

        assertThat(patch(id, Map.of("weight", 11)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateTenant_nonexistent_returns404() {
        assertThat(patch(UUID.randomUUID().toString(), Map.of("weight", 2)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private Map<String, Object> tenantBody(String label) {
        String slug = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Tenant " + slug);
        body.put("slug", slug);
        body.put("rateLimitPerSec", 100);
        body.put("burst", 200);
        body.put("weight", 1);
        body.put("maxAttempts", 5);
        return body;
    }

    private ResponseEntity<JsonNode> createTenant(Map<String, Object> body) {
        return asPlatformAdmin().postForEntity(TENANTS, body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(String id, Map<String, Object> body) {
        return asPlatformAdmin().exchange(TENANTS + "/" + id, HttpMethod.PATCH, new HttpEntity<>(body), JsonNode.class);
    }
}

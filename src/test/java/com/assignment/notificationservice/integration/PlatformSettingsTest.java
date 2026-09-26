package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
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

class PlatformSettingsTest extends BaseIntegrationTest {

    private static final String SETTINGS = "/api/v1/admin/settings";

    @Autowired
    private JdbcTemplate jdbc;

    /** Restore the V005 seed values: tenant-creation tests elsewhere depend on them. */
    @AfterEach
    void restoreSeedSettings() {
        jdbc.update("UPDATE platform_setting SET value = '1000' WHERE key = 'max_tenant_rate_per_sec'");
        jdbc.update("UPDATE platform_setting SET value = '5' WHERE key = 'default_max_attempts'");
    }

    @Test
    void getSettings_returnsSeedValues() {
        JsonNode settings = asPlatformAdmin().getForObject(SETTINGS, JsonNode.class);

        assertThat(settings.get("maxTenantRatePerSec").asInt()).isEqualTo(1000);
        assertThat(settings.get("defaultMaxAttempts").asInt()).isEqualTo(5);
    }

    @Test
    void updateSettings_partialUpdate() {
        ResponseEntity<JsonNode> res = put(Map.of("maxTenantRatePerSec", 500));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("maxTenantRatePerSec").asInt()).isEqualTo(500);
        assertThat(res.getBody().get("defaultMaxAttempts").asInt()).isEqualTo(5);
        assertThat(asPlatformAdmin().getForObject(SETTINGS, JsonNode.class).get("maxTenantRatePerSec").asInt())
                .isEqualTo(500);
    }

    @Test
    void updateSettings_outOfRange_returns400() {
        assertThat(put(Map.of("maxTenantRatePerSec", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(Map.of("defaultMaxAttempts", 21)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loweredRateCap_isEnforcedOnTenantCreation() {
        put(Map.of("maxTenantRatePerSec", 50));

        assertThat(createTenant(Map.of("rateLimitPerSec", 60, "burst", 60)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTenant(Map.of("rateLimitPerSec", 50, "burst", 60)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void defaultMaxAttempts_isAppliedToNewTenantsThatOmitIt() {
        put(Map.of("defaultMaxAttempts", 9));

        JsonNode tenant = createTenant(Map.of("rateLimitPerSec", 10, "burst", 10)).getBody();

        assertThat(tenant.get("maxAttempts").asInt()).isEqualTo(9);
    }

    private ResponseEntity<JsonNode> put(Map<String, Object> body) {
        return asPlatformAdmin().exchange(SETTINGS, HttpMethod.PUT, new HttpEntity<>(body), JsonNode.class);
    }

    private ResponseEntity<JsonNode> createTenant(Map<String, Object> limits) {
        String slug = "settings-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new java.util.HashMap<>(limits);
        body.put("name", "Tenant " + slug);
        body.put("slug", slug);
        return asPlatformAdmin().postForEntity("/api/v1/admin/tenants", body, JsonNode.class);
    }
}

package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.constants.SecurityConstants;
import com.assignment.notificationservice.dtos.ApiKeyCreateResponse;
import com.assignment.notificationservice.dtos.ApiKeyResponse;
import com.assignment.notificationservice.dtos.ChannelConfigResponse;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant admins see only their own tenant; platform and tenant scopes do not overlap;
 * anonymous callers get 401. Cross-tenant reads answer 404, never 403, so they reveal
 * nothing about whether the resource exists.
 */
class RbacIsolationTest extends BaseIntegrationTest {

    private static final String TEMPLATES = "/api/v1/tenant/templates";
    private static final String CHANNELS = "/api/v1/tenant/channels";
    private static final String API_KEYS = "/api/v1/tenant/api-keys";
    private static final String ADMIN_TENANTS = "/api/v1/admin/tenants";

    private TestTenant tenantA;
    private TestTenant tenantB;

    @BeforeEach
    void setUp() {
        tenantA = setupTenant("rbac-a");
        tenantB = setupTenant("rbac-b");
    }

    @Test
    void tenantAdminA_cannotSeeTenantB_templates() {
        String templateId = createTemplate(tenantA);

        assertThat(as(tenantB).getForEntity(TEMPLATES + "/" + templateId, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(tenantB).getForObject(TEMPLATES, JsonNode.class).get("totalElements").asLong())
                .isZero();
    }

    @Test
    void tenantAdminB_cannotModifyTenantA_templates() {
        String templateId = createTemplate(tenantA);

        assertThat(as(tenantB).exchange(TEMPLATES + "/" + templateId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("body", "hijacked")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(tenantB).exchange(TEMPLATES + "/" + templateId, HttpMethod.DELETE,
                null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(tenantB).postForEntity(TEMPLATES + "/" + templateId + "/preview",
                Map.of("variables", Map.of("name", "x")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode stillMine = as(tenantA).getForObject(TEMPLATES + "/" + templateId, JsonNode.class);
        assertThat(stillMine.get("active").asBoolean()).isTrue();
        assertThat(stillMine.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void tenantAdminA_cannotSeeTenantB_channels() {
        as(tenantA).exchange(CHANNELS + "/PUSH", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true)), ChannelConfigResponse.class);

        ChannelConfigResponse[] bChannels = as(tenantB).getForObject(CHANNELS, ChannelConfigResponse[].class);
        ChannelConfigResponse bPush = Arrays.stream(bChannels)
                .filter(c -> c.channel() == Channel.PUSH).findFirst().orElseThrow();
        assertThat(bPush.enabled()).isFalse();
    }

    @Test
    void tenantAdminA_cannotSeeTenantB_apiKeys() {
        ApiKeyCreateResponse aKey = as(tenantA).postForObject(API_KEYS, Map.of("name", "a"),
                ApiKeyCreateResponse.class);

        ApiKeyResponse[] bKeys = as(tenantB).getForObject(API_KEYS, ApiKeyResponse[].class);

        assertThat(bKeys).isEmpty();
        assertThat(as(tenantA).getForObject(API_KEYS, ApiKeyResponse[].class))
                .extracting(ApiKeyResponse::id).containsExactly(aKey.id());
    }

    @Test
    void tenantAdmin_cannotAccessPlatformAdminEndpoints() {
        assertThat(as(tenantA).getForEntity(ADMIN_TENANTS, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void platformAdmin_cannotAccessTenantEndpoints() {
        assertThat(asPlatformAdmin().getForEntity(TEMPLATES, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asPlatformAdmin().getForEntity(CHANNELS, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asPlatformAdmin().getForEntity(API_KEYS, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void platformAdmin_passesAuthorizationOnAdminEndpoints() {
        assertThat(asPlatformAdmin().getForEntity(ADMIN_TENANTS, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void unauthenticated_returns401() {
        for (String path : new String[]{TEMPLATES, CHANNELS, API_KEYS, ADMIN_TENANTS, "/api/v1/notifications"}) {
            assertThat(restTemplate.getForEntity(path, String.class).getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(restTemplate.postForEntity(TEMPLATES, Map.of("code", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongPassword_returns401() {
        assertThat(restTemplate.withBasicAuth(tenantA.username(), "wrong")
                .getForEntity(TEMPLATES, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiKey_cannotAccessTenantConsole() {
        ApiKeyCreateResponse key = as(tenantA).postForObject(API_KEYS, Map.of("name", "a"),
                ApiKeyCreateResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set(SecurityConstants.API_KEY_HEADER, key.rawKey());

        assertThat(restTemplate.exchange(TEMPLATES, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String createTemplate(TestTenant t) {
        JsonNode created = as(t).postForObject(TEMPLATES, Map.of(
                "code", "secret_template",
                "channel", "EMAIL",
                "subject", "Hi {{name}}",
                "body", "Hello {{name}}"), JsonNode.class);
        return created.get("id").asText();
    }
}

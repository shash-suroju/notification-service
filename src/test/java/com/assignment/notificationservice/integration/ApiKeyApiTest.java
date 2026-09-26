package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.constants.SecurityConstants;
import com.assignment.notificationservice.dtos.ApiKeyCreateResponse;
import com.assignment.notificationservice.dtos.ApiKeyResponse;
import com.assignment.notificationservice.models.enums.ApiKeyStatus;
import com.assignment.notificationservice.support.TestTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API key lifecycle, plus authentication on the send API. {@code /api/v1/notifications}
 * has no controller yet, so a valid key yields 404 (authenticated, no route) and an
 * invalid one 401 — exactly the distinction these tests need.
 */
class ApiKeyApiTest extends BaseIntegrationTest {

    private static final String BASE = "/api/v1/tenant/api-keys";
    private static final String SEND_API = "/api/v1/notifications";
    private static final String SEED_ACME_API_KEY = "ntfy_acme1234_testkey12345678901234567890ab";

    private TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = setupTenant("keys");
    }

    @Test
    void createApiKey_returnsRawKeyOnce() {
        ResponseEntity<ApiKeyCreateResponse> res = createKey(tenant, "ci-key");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ApiKeyCreateResponse key = res.getBody();
        assertThat(key.rawKey()).startsWith("ntfy_").hasSize(46);
        assertThat(key.rawKey()).matches("ntfy_[a-z0-9]{8}_[a-z0-9]{32}");
        assertThat(key.rawKey().substring(5, 13)).isEqualTo(key.prefix());
        assertThat(key.name()).isEqualTo("ci-key");
    }

    @Test
    void createApiKey_withoutBody_succeeds() {
        ResponseEntity<ApiKeyCreateResponse> res =
                as(tenant).postForEntity(BASE, null, ApiKeyCreateResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().name()).isNull();
    }

    @Test
    void listApiKeys_doesNotExposeRawKey() {
        ApiKeyCreateResponse created = createKey(tenant, "listed").getBody();

        String json = as(tenant).getForObject(BASE, String.class);

        assertThat(json).contains(created.prefix());
        assertThat(json).doesNotContain(created.rawKey());
        assertThat(json).doesNotContain("rawKey").doesNotContain("keyHash");
    }

    @Test
    void revokeApiKey_softDeletes() {
        ApiKeyCreateResponse created = createKey(tenant, "doomed").getBody();

        ResponseEntity<Void> res = as(tenant).exchange(
                BASE + "/" + created.id(), HttpMethod.DELETE, null, Void.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ApiKeyResponse[] keys = as(tenant).getForObject(BASE, ApiKeyResponse[].class);
        assertThat(keys).singleElement().satisfies(k -> {
            assertThat(k.id()).isEqualTo(created.id());
            assertThat(k.status()).isEqualTo(ApiKeyStatus.REVOKED);
        });
    }

    @Test
    void revokedKeyCannotAuthenticate() {
        ApiKeyCreateResponse created = createKey(tenant, "short-lived").getBody();
        assertThat(callSendApi(created.rawKey())).isEqualTo(HttpStatus.NOT_FOUND);

        as(tenant).exchange(BASE + "/" + created.id(), HttpMethod.DELETE, null, Void.class);

        assertThat(callSendApi(created.rawKey())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validKeyAuthenticates() {
        ApiKeyCreateResponse created = createKey(tenant, "valid").getBody();

        // Authenticated, but no send controller exists yet → 404, not 401.
        assertThat(callSendApi(created.rawKey())).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void seededAcmeKeyAuthenticates() {
        assertThat(callSendApi(SEED_ACME_API_KEY)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidKeys_return401() {
        ApiKeyCreateResponse created = createKey(tenant, "real").getBody();
        String tampered = created.rawKey().substring(0, created.rawKey().length() - 1)
                + (created.rawKey().endsWith("a") ? "b" : "a");

        assertThat(callSendApi(tampered)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callSendApi("ntfy_nosuchpx_" + "x".repeat(32))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callSendApi("not-even-the-right-format")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callSendApi(null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiKeyUpdatesLastUsedAt() {
        ApiKeyCreateResponse created = createKey(tenant, "tracked").getBody();
        assertThat(as(tenant).getForObject(BASE, ApiKeyResponse[].class)[0].lastUsedAt()).isNull();

        Instant usedAt = clock.advance(Duration.ofMinutes(3));
        callSendApi(created.rawKey());

        ApiKeyResponse key = as(tenant).getForObject(BASE, ApiKeyResponse[].class)[0];
        assertThat(key.lastUsedAt()).isEqualTo(usedAt);
    }

    @Test
    void revokingAnotherTenantsKey_returns404AndLeavesItActive() {
        TestTenant other = setupTenant("keys-other");
        ApiKeyCreateResponse created = createKey(tenant, "mine").getBody();

        ResponseEntity<Void> res = as(other).exchange(
                BASE + "/" + created.id(), HttpMethod.DELETE, null, Void.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(callSendApi(created.rawKey())).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private ResponseEntity<ApiKeyCreateResponse> createKey(TestTenant t, String name) {
        return as(t).postForEntity(BASE, Map.of("name", name), ApiKeyCreateResponse.class);
    }

    /** Calls the send API with only an API key (no Basic credentials). */
    private HttpStatusCode callSendApi(String rawKey) {
        HttpHeaders headers = new HttpHeaders();
        if (rawKey != null) {
            headers.set(SecurityConstants.API_KEY_HEADER, rawKey);
        }
        return restTemplate.exchange(SEND_API, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode();
    }
}

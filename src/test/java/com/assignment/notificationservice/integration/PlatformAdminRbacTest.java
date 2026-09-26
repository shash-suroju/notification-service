package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.support.TestSender;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform console is for PLATFORM_ADMIN only — never tenant admins, API keys or anonymous callers. */
class PlatformAdminRbacTest extends BaseIntegrationTest {

    private static final String[] ADMIN_READS = {
            "/api/v1/admin/tenants",
            "/api/v1/admin/tenants/10000000-0000-0000-0000-000000000001",
            "/api/v1/admin/tenants/10000000-0000-0000-0000-000000000001/admins",
            "/api/v1/admin/global-limits",
            "/api/v1/admin/settings"};

    @Test
    void tenantAdmin_cannotAccessAdminEndpoints() {
        var acmeAdmin = restTemplate.withBasicAuth("acme-admin", SEED_PASSWORD);

        for (String path : ADMIN_READS) {
            assertThat(acmeAdmin.getForEntity(path, String.class).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        // Writes are refused too, and nothing changes.
        assertThat(acmeAdmin.postForEntity("/api/v1/admin/tenants/10000000-0000-0000-0000-000000000001/suspend",
                null, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(acmeAdmin.exchange("/api/v1/admin/settings", HttpMethod.PUT,
                new HttpEntity<>(Map.of("maxTenantRatePerSec", 1)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asPlatformAdmin().getForObject("/api/v1/admin/settings", Map.class).get("maxTenantRatePerSec"))
                .isEqualTo(1000);
    }

    @Test
    void apiKey_cannotAccessAdminEndpoints() {
        TestSender sender = setupSender("rbac-key");

        for (String path : ADMIN_READS) {
            HttpStatusCode status = restTemplate.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), String.class).getStatusCode();
            // The API key filter only runs on the send API, so here the caller is simply unauthenticated.
            assertThat(status).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void platformAdmin_cannotAccessTenantEndpoints() {
        assertThat(asPlatformAdmin().getForEntity("/api/v1/tenant/templates", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void noAuth_returns401() {
        for (String path : ADMIN_READS) {
            assertThat(restTemplate.getForEntity(path, String.class).getStatusCode()).as(path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(restTemplate.postForEntity("/api/v1/admin/tenants", Map.of("name", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void platformAdmin_wrongPassword_returns401() {
        assertThat(restTemplate.withBasicAuth(SEED_PLATFORM_ADMIN, "nope").getForEntity("/api/v1/admin/tenants", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

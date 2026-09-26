package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAdminUserTest extends BaseIntegrationTest {

    private static final String TENANTS = "/api/v1/admin/tenants";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createTenantAdmin_success() {
        String tenantId = createTenant("admins");
        String username = uniqueUsername();

        ResponseEntity<JsonNode> res = createAdmin(tenantId, username, PASSWORD);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode admin = res.getBody();
        assertThat(admin.get("username").asText()).isEqualTo(username);
        assertThat(admin.get("role").asText()).isEqualTo("TENANT_ADMIN");
        assertThat(admin.get("tenantId").asText()).isEqualTo(tenantId);
        assertThat(admin.toString()).doesNotContain(PASSWORD).doesNotContainIgnoringCase("password");

        // Stored as a BCrypt hash, never in clear.
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, username);
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void createTenantAdmin_duplicateUsername_returns409() {
        String tenantId = createTenant("dup-admin");
        String username = uniqueUsername();
        createAdmin(tenantId, username, PASSWORD);

        assertThat(createAdmin(tenantId, username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Usernames are global: the same name under another tenant also conflicts.
        assertThat(createAdmin(createTenant("other"), username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createTenantAdmin_weakOrMalformedInput_returns400() {
        String tenantId = createTenant("weak");

        ResponseEntity<JsonNode> res = createAdmin(tenantId, "a b", "short");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fieldErrors").has("username")).isTrue();
        assertThat(res.getBody().get("fieldErrors").has("password")).isTrue();
    }

    @Test
    void createdAdmin_canLoginAndAccessTenantAPIs() {
        String tenantId = createTenant("login");
        String username = uniqueUsername();
        createAdmin(tenantId, username, PASSWORD);
        TestRestTemplate admin = restTemplate.withBasicAuth(username, PASSWORD);

        assertThat(admin.getForEntity("/api/v1/tenant/templates", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Their channel view is the auto-seeded one: all four enabled.
        JsonNode channels = admin.getForObject("/api/v1/tenant/channels", JsonNode.class);
        assertThat(channels).hasSize(4).allSatisfy(c -> assertThat(c.get("enabled").asBoolean()).isTrue());

        assertThat(restTemplate.withBasicAuth(username, "wrong-password")
                .getForEntity("/api/v1/tenant/templates", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createdAdmin_cannotAccessOtherTenant() {
        String tenantA = createTenant("iso-a");
        String tenantB = createTenant("iso-b");
        String adminA = uniqueUsername();
        String adminB = uniqueUsername();
        createAdmin(tenantA, adminA, PASSWORD);
        createAdmin(tenantB, adminB, PASSWORD);
        String templateOfB = restTemplate.withBasicAuth(adminB, PASSWORD).postForObject("/api/v1/tenant/templates",
                Map.of("code", "b_only", "channel", "SMS", "body", "hi"), JsonNode.class).get("id").asText();

        TestRestTemplate asA = restTemplate.withBasicAuth(adminA, PASSWORD);
        assertThat(asA.getForObject("/api/v1/tenant/templates", JsonNode.class).get("totalElements").asInt()).isZero();
        assertThat(asA.getForEntity("/api/v1/tenant/templates/" + templateOfB, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // ...and a tenant admin cannot use the platform console to reach B either.
        assertThat(asA.getForEntity(TENANTS + "/" + tenantB, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listTenantAdmins_returnsOnlyAdminsForThatTenant() {
        String tenantA = createTenant("list-a");
        String tenantB = createTenant("list-b");
        String a1 = uniqueUsername();
        String a2 = uniqueUsername();
        createAdmin(tenantA, a1, PASSWORD);
        createAdmin(tenantA, a2, PASSWORD);
        createAdmin(tenantB, uniqueUsername(), PASSWORD);

        JsonNode admins = asPlatformAdmin().getForObject(TENANTS + "/" + tenantA + "/admins", JsonNode.class);

        List<String> usernames = new ArrayList<>();
        admins.forEach(a -> usernames.add(a.get("username").asText()));
        assertThat(usernames).containsExactlyInAnyOrder(a1, a2);
        assertThat(admins).allSatisfy(a -> assertThat(a.get("tenantId").asText()).isEqualTo(tenantA));
    }

    @Test
    void createAdmin_forNonexistentTenant_returns404() {
        assertThat(createAdmin(UUID.randomUUID().toString(), uniqueUsername(), PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(asPlatformAdmin().getForEntity(TENANTS + "/" + UUID.randomUUID() + "/admins", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String createTenant(String label) {
        String slug = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return asPlatformAdmin().postForObject(TENANTS, Map.of(
                "name", "Tenant " + slug, "slug", slug, "rateLimitPerSec", 10, "burst", 20), JsonNode.class)
                .get("id").asText();
    }

    private ResponseEntity<JsonNode> createAdmin(String tenantId, String username, String password) {
        return asPlatformAdmin().postForEntity(TENANTS + "/" + tenantId + "/admins",
                Map.of("username", username, "password", password), JsonNode.class);
    }

    private static String uniqueUsername() {
        return "admin-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

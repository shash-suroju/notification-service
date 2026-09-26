package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateApiTest extends BaseIntegrationTest {

    private static final String BASE = "/api/v1/tenant/templates";

    private TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = setupTenant("tmpl");
    }

    @Test
    void createTemplate_success() {
        ResponseEntity<JsonNode> res = create(tenant, emailTemplate("welcome"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = res.getBody();
        assertThat(body.get("id").asText()).isNotBlank();
        assertThat(body.get("code").asText()).isEqualTo("welcome");
        assertThat(body.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("body").asText()).isEqualTo("Hello {{name}}, order {{orderId}}");
        assertThat(body.get("active").asBoolean()).isTrue();
        assertThat(textValues(body.get("requiredVariables"))).containsExactly("name", "orderId");
        assertThat(body.get("createdAt").asText()).isEqualTo(clock.instant().toString());
    }

    @Test
    void createTemplate_duplicateCodeAndChannel_returns409() {
        assertThat(create(tenant, emailTemplate("welcome")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> dup = create(tenant, emailTemplate("welcome"));

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("detail").asText()).contains("already exists");
    }

    @Test
    void createTemplate_sameCodeDifferentChannel_isAllowed() {
        assertThat(create(tenant, emailTemplate("receipt")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(tenant, Map.of("code", "receipt", "channel", "SMS", "body", "Paid"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void createTemplate_emailWithoutSubject_returns400() {
        ResponseEntity<JsonNode> res = create(tenant,
                Map.of("code", "nosubject", "channel", "EMAIL", "body", "Hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).contains("Subject is required");
    }

    @Test
    void createTemplate_smsWithoutSubject_succeeds() {
        ResponseEntity<JsonNode> res = create(tenant,
                Map.of("code", "otp", "channel", "SMS", "body", "Your code is {{code}}"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().has("subject")).isFalse();
    }

    @Test
    void createTemplate_invalidCode_returns400() {
        ResponseEntity<JsonNode> res = create(tenant,
                Map.of("code", "Bad-Code!", "channel", "SMS", "body", "x"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Validation Failed");
        assertThat(res.getBody().get("fieldErrors").has("code")).isTrue();
    }

    @Test
    void createTemplate_missingRequiredFields_returns400WithEveryField() {
        ResponseEntity<JsonNode> res = create(tenant, Map.of());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode fieldErrors = res.getBody().get("fieldErrors");
        assertThat(fieldErrors.has("code")).isTrue();
        assertThat(fieldErrors.has("channel")).isTrue();
        assertThat(fieldErrors.has("body")).isTrue();
    }

    @Test
    void getTemplate_returnsCorrectData() {
        JsonNode created = create(tenant, emailTemplate("welcome")).getBody();

        ResponseEntity<JsonNode> res = as(tenant).getForEntity(BASE + "/" + id(created), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(created);
    }

    @Test
    void getTemplate_unknownId_returns404() {
        ResponseEntity<JsonNode> res = as(tenant).getForEntity(
                BASE + "/00000000-0000-0000-0000-00000000dead", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTemplate_malformedId_returns400() {
        ResponseEntity<JsonNode> res = as(tenant).getForEntity(BASE + "/not-a-uuid", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getTemplate_otherTenantId_returns404() {
        TestTenant other = setupTenant("other");
        JsonNode created = create(tenant, emailTemplate("welcome")).getBody();

        ResponseEntity<JsonNode> res = as(other).getForEntity(BASE + "/" + id(created), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listTemplates_paginatedAndFiltered() {
        String first = id(create(tenant, emailTemplate("alpha")).getBody());
        create(tenant, emailTemplate("bravo"));
        create(tenant, emailTemplate("charlie"));
        // A new version must not appear as an extra row — the list shows latest versions only.
        put(tenant, first, Map.of("body", "Alpha v2"));

        JsonNode page0 = as(tenant).getForObject(BASE + "?page=0&size=2", JsonNode.class);
        JsonNode page1 = as(tenant).getForObject(BASE + "?page=1&size=2", JsonNode.class);

        assertThat(page0.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("content")).hasSize(2);
        assertThat(page1.get("content")).hasSize(1);

        List<String> codes = new ArrayList<>();
        page0.get("content").forEach(t -> codes.add(t.get("code").asText()));
        page1.get("content").forEach(t -> codes.add(t.get("code").asText()));
        assertThat(codes).containsExactly("alpha", "bravo", "charlie");
        assertThat(page0.get("content").get(0).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void listTemplates_pageSizeIsCappedAt100() {
        JsonNode page = as(tenant).getForObject(BASE + "?size=5000", JsonNode.class);

        assertThat(page.get("size").asInt()).isEqualTo(100);
    }

    @Test
    void updateTemplate_createsNewVersion() {
        String v1Id = id(create(tenant, emailTemplate("welcome")).getBody());

        ResponseEntity<JsonNode> res = put(tenant, v1Id, Map.of("body", "Hi {{name}}, v2"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode v2 = res.getBody();
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(id(v2)).isNotEqualTo(v1Id);
        assertThat(v2.get("body").asText()).isEqualTo("Hi {{name}}, v2");
        // Omitted subject is carried over from v1
        assertThat(v2.get("subject").asText()).isEqualTo("Hi {{name}}");

        assertThat(get(tenant, v1Id).get("active").asBoolean()).isFalse();
        assertThat(get(tenant, id(v2)).get("active").asBoolean()).isTrue();
    }

    @Test
    void updateTemplate_emptyBody_returns400() {
        String v1Id = id(create(tenant, emailTemplate("welcome")).getBody());

        ResponseEntity<JsonNode> res = put(tenant, v1Id, Map.of());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteTemplate_deactivates() {
        String templateId = id(create(tenant, emailTemplate("welcome")).getBody());

        ResponseEntity<Void> res = as(tenant).exchange(
                BASE + "/" + templateId, HttpMethod.DELETE, null, Void.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(tenant, templateId).get("active").asBoolean()).isFalse();
    }

    @Test
    void previewTemplate_rendersWithVariables() {
        String templateId = id(create(tenant, emailTemplate("welcome")).getBody());

        ResponseEntity<JsonNode> res = preview(tenant, templateId,
                Map.of("name", "Alice <3", "orderId", "A-17"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(body.get("renderedSubject").asText()).isEqualTo("Hi Alice &lt;3");
        assertThat(body.get("renderedBody").asText()).isEqualTo("Hello Alice &lt;3, order A-17");
        assertThat(textValues(body.get("bodyVariables"))).containsExactly("name", "orderId");
        assertThat(textValues(body.get("subjectVariables"))).containsExactly("name");
    }

    @Test
    void previewTemplate_missingVariable_returns400() {
        String templateId = id(create(tenant, emailTemplate("welcome")).getBody());

        ResponseEntity<JsonNode> res = preview(tenant, templateId, Map.of());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Missing Template Variable");
        assertThat(res.getBody().get("variableName").asText()).isEqualTo("name");
    }

    @Test
    void previewTemplate_smsLengthExceeded_returns400() {
        String templateId = id(create(tenant,
                Map.of("code", "long_sms", "channel", "SMS", "body", "Hi {{name}}")).getBody());

        ResponseEntity<JsonNode> res = preview(tenant, templateId, Map.of("name", "x".repeat(500)));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).contains("SMS body too long");
    }

    @Test
    void getVersionHistory_returnsAllVersions() {
        String v1Id = id(create(tenant, emailTemplate("welcome")).getBody());
        String v2Id = id(put(tenant, v1Id, Map.of("body", "v2")).getBody());
        put(tenant, v2Id, Map.of("body", "v3"));

        JsonNode versions = as(tenant).getForObject(BASE + "/" + v1Id + "/versions", JsonNode.class);

        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(3);
        assertThat(versions.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(2).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(0).get("active").asBoolean()).isTrue();
        assertThat(versions.get(1).get("active").asBoolean()).isFalse();
        assertThat(versions.get(2).get("active").asBoolean()).isFalse();
    }

    // ---- helpers ----

    private static Map<String, Object> emailTemplate(String code) {
        return Map.of(
                "code", code,
                "channel", "EMAIL",
                "subject", "Hi {{name}}",
                "body", "Hello {{name}}, order {{orderId}}");
    }

    private ResponseEntity<JsonNode> create(TestTenant t, Map<String, Object> body) {
        return as(t).postForEntity(BASE, body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(TestTenant t, String templateId, Map<String, Object> body) {
        return as(t).exchange(BASE + "/" + templateId, HttpMethod.PUT, new HttpEntity<>(body), JsonNode.class);
    }

    private JsonNode get(TestTenant t, String templateId) {
        return as(t).getForObject(BASE + "/" + templateId, JsonNode.class);
    }

    private ResponseEntity<JsonNode> preview(TestTenant t, String templateId, Map<String, String> vars) {
        return as(t).postForEntity(BASE + "/" + templateId + "/preview",
                Map.of("variables", vars), JsonNode.class);
    }

    private static String id(JsonNode node) {
        return node.get("id").asText();
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}

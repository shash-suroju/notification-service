package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.dtos.ChannelConfigResponse;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelConfigApiTest extends BaseIntegrationTest {

    private static final String BASE = "/api/v1/tenant/channels";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = setupTenant("chan");
    }

    @Test
    void listChannels_returnsAllFourChannels() {
        ResponseEntity<ChannelConfigResponse[]> res = as(tenant).getForEntity(BASE, ChannelConfigResponse[].class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).extracting(ChannelConfigResponse::channel)
                .containsExactly(Channel.EMAIL, Channel.SMS, Channel.PUSH, Channel.IN_APP);
        // A brand-new tenant has configured nothing, so everything reads as disabled.
        assertThat(res.getBody()).allSatisfy(c -> {
            assertThat(c.enabled()).isFalse();
            assertThat(c.id()).isNull();
        });
    }

    @Test
    void enableChannel_createsConfigIfNotExists() {
        ResponseEntity<ChannelConfigResponse> res = put(tenant, "PUSH", Map.of("enabled", true));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().enabled()).isTrue();
        assertThat(res.getBody().id()).isNotNull();

        assertThat(find(tenant, Channel.PUSH).enabled()).isTrue();
    }

    @Test
    void channelPathIsCaseInsensitive() {
        ResponseEntity<ChannelConfigResponse> res = put(tenant, "in_app", Map.of("enabled", true));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().channel()).isEqualTo(Channel.IN_APP);
    }

    @Test
    void disableChannel() {
        put(tenant, "EMAIL", Map.of("enabled", true));

        ResponseEntity<ChannelConfigResponse> res = put(tenant, "EMAIL", Map.of("enabled", false));

        assertThat(res.getBody().enabled()).isFalse();
        assertThat(find(tenant, Channel.EMAIL).enabled()).isFalse();
    }

    @Test
    void updateChannelSettings() {
        ResponseEntity<ChannelConfigResponse> res = put(tenant, "EMAIL",
                Map.of("enabled", true, "settings", Map.of("fromAddress", "noreply@acme.com")));

        assertThat(res.getBody().settings()).containsEntry("fromAddress", "noreply@acme.com");

        // Stored as real jsonb, queryable with Postgres JSON operators
        String stored = jdbcTemplate.queryForObject(
                "SELECT settings ->> 'fromAddress' FROM channel_config WHERE tenant_id = ? AND channel = 'EMAIL'",
                String.class, tenant.id());
        assertThat(stored).isEqualTo("noreply@acme.com");
    }

    @Test
    void omittedSettingsAreLeftUnchanged() {
        put(tenant, "SMS", Map.of("enabled", true, "settings", Map.of("senderId", "ACME")));

        ResponseEntity<ChannelConfigResponse> res = put(tenant, "SMS", Map.of("enabled", false));

        assertThat(res.getBody().enabled()).isFalse();
        assertThat(res.getBody().settings()).containsEntry("senderId", "ACME");
    }

    @Test
    void unknownChannel_returns400() {
        ResponseEntity<JsonNode> res = as(tenant).exchange(BASE + "/FAX", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).isEqualTo("Unknown channel: FAX");
    }

    @Test
    void missingEnabledFlag_returns400() {
        ResponseEntity<JsonNode> res = as(tenant).exchange(BASE + "/EMAIL", HttpMethod.PUT,
                new HttpEntity<>(Map.of()), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fieldErrors").has("enabled")).isTrue();
    }

    @Test
    void channelConfig_otherTenant_isolated() {
        TestTenant other = setupTenant("chan-other");

        put(tenant, "PUSH", Map.of("enabled", true));

        ChannelConfigResponse otherPush = find(other, Channel.PUSH);
        assertThat(otherPush.enabled()).isFalse();
        assertThat(otherPush.id()).isNull();
    }

    // ---- helpers ----

    private ResponseEntity<ChannelConfigResponse> put(TestTenant t, String channel, Map<String, Object> body) {
        return as(t).exchange(BASE + "/" + channel, HttpMethod.PUT, new HttpEntity<>(body),
                ChannelConfigResponse.class);
    }

    private ChannelConfigResponse find(TestTenant t, Channel channel) {
        ChannelConfigResponse[] all = as(t).getForObject(BASE, ChannelConfigResponse[].class);
        return Arrays.stream(all).filter(c -> c.channel() == channel).findFirst().orElseThrow();
    }
}

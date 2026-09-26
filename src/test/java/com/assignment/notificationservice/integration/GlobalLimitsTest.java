package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.RateLimiterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;

class GlobalLimitsTest extends BaseIntegrationTest {

    private static final String LIMITS = "/api/v1/admin/global-limits";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    /** Restore the V004 seed values: other test classes rely on them. */
    @AfterEach
    void restoreSeedLimits() {
        jdbc.update("UPDATE global_channel_limit SET rate_per_sec = 500, burst = 1000 WHERE channel IN ('EMAIL', 'PUSH')");
        jdbc.update("UPDATE global_channel_limit SET rate_per_sec = 200, burst = 400 WHERE channel = 'SMS'");
        jdbc.update("UPDATE global_channel_limit SET rate_per_sec = 1000, burst = 2000 WHERE channel = 'IN_APP'");
        rateLimiterRegistry.resetAll();
    }

    @Test
    void listGlobalLimits_returnsSeedData() {
        JsonNode limits = asPlatformAdmin().getForObject(LIMITS, JsonNode.class);

        assertThat(limits).hasSize(4);
        assertThat(limit(limits, 0)).isEqualTo("EMAIL 500/1000");
        assertThat(limit(limits, 1)).isEqualTo("SMS 200/400");
        assertThat(limit(limits, 2)).isEqualTo("PUSH 500/1000");
        assertThat(limit(limits, 3)).isEqualTo("IN_APP 1000/2000");
    }

    @Test
    void updateGlobalLimit_success() {
        ResponseEntity<JsonNode> res = put("EMAIL", Map.of("ratePerSec", 250, "burst", 600));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("ratePerSec").asInt()).isEqualTo(250);
        assertThat(res.getBody().get("burst").asInt()).isEqualTo(600);
        assertThat(limit(asPlatformAdmin().getForObject(LIMITS, JsonNode.class), 0)).isEqualTo("EMAIL 250/600");
    }

    @Test
    void updateGlobalLimit_takesEffectInTheDispatcherImmediately() {
        assertThat(rateLimiterRegistry.channelBucket(Channel.SMS).getBurst()).isEqualTo(400);   // cached bucket

        put("sms", Map.of("ratePerSec", 7, "burst", 9));   // lower-case path works too

        // The cached bucket was dropped after commit and rebuilt from the new limits.
        assertThat(rateLimiterRegistry.channelBucket(Channel.SMS).getBurst()).isEqualTo(9);
        assertThat(rateLimiterRegistry.channelBucket(Channel.SMS).getRatePerSec()).isEqualTo(7);
    }

    @Test
    void updateGlobalLimit_burstLessThanRate_returns400() {
        ResponseEntity<JsonNode> res = put("EMAIL", Map.of("ratePerSec", 100, "burst", 50));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fieldErrors").get("burstValid").asText()).isEqualTo("burst must be >= ratePerSec");
    }

    @Test
    void updateGlobalLimit_missingOrNonPositiveValues_returns400() {
        assertThat(put("EMAIL", Map.of("ratePerSec", 0, "burst", 10)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("EMAIL", Map.of("burst", 10)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateGlobalLimit_invalidChannel_returns400() {
        ResponseEntity<JsonNode> res = put("INVALID", Map.of("ratePerSec", 10, "burst", 10));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("detail").asText()).isEqualTo("Unknown channel: INVALID");
    }

    private ResponseEntity<JsonNode> put(String channel, Map<String, Object> body) {
        return asPlatformAdmin().exchange(LIMITS + "/" + channel, HttpMethod.PUT, new HttpEntity<>(body), JsonNode.class);
    }

    private static String limit(JsonNode limits, int i) {
        JsonNode l = limits.get(i);
        return l.get("channel").asText() + " " + l.get("ratePerSec").asInt() + "/" + l.get("burst").asInt();
    }
}

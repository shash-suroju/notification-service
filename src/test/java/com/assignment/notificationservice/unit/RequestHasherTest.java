package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.utils.RequestHasher;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    @Test
    void hash_stableAcrossKeyOrder() {
        Map<String, String> vars1 = new LinkedHashMap<>();
        vars1.put("a", "1");
        vars1.put("b", "2");
        Map<String, String> vars2 = new LinkedHashMap<>();
        vars2.put("b", "2");
        vars2.put("a", "1");

        String h1 = RequestHasher.hash("EMAIL", "test@example.com", "welcome", vars1);
        String h2 = RequestHasher.hash("EMAIL", "test@example.com", "welcome", vars2);

        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void hash_isDeterministic() {
        Map<String, String> vars = Map.of("name", "Alice");
        assertThat(RequestHasher.hash("SMS", "+15550001111", "otp", vars))
                .isEqualTo(RequestHasher.hash("SMS", "+15550001111", "otp", vars));
    }

    @Test
    void hash_differentPayloadsDifferentHash() {
        Map<String, String> vars = Map.of("name", "Alice");
        String base = RequestHasher.hash("EMAIL", "a@example.com", "welcome", vars);

        assertThat(RequestHasher.hash("EMAIL", "b@example.com", "welcome", vars)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("SMS", "a@example.com", "welcome", vars)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("EMAIL", "a@example.com", "goodbye", vars)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("EMAIL", "a@example.com", "welcome", Map.of("name", "Bob")))
                .isNotEqualTo(base);
    }

    @Test
    void hash_nullVariables() {
        assertThat(RequestHasher.hash("EMAIL", "a@example.com", "welcome", null))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void hash_emptyVariables() {
        assertThat(RequestHasher.hash("EMAIL", "a@example.com", "welcome", Map.of()))
                .isEqualTo(RequestHasher.hash("EMAIL", "a@example.com", "welcome", null));
    }

    @Test
    void hash_is64CharHexString() {
        assertThat(RequestHasher.hash("PUSH", "device-token", "alert", Map.of("k", "v")))
                .hasSize(64)
                .matches("[0-9a-f]+");
    }
}

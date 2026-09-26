package com.assignment.notificationservice.utils;


import java.util.Map;

/**
 * Stable fingerprint of a send request, stored next to the idempotency key.
 * Same key + same hash = client retry (return existing); same key + different hash =
 * key reuse for a different message (reject with 422).
 */
public final class RequestHasher {

    private RequestHasher() {
        // static utility
    }

    /**
     * Canonical form: {@code channel|recipient|templateCode|k1=v1,k2=v2,...} with variables
     * sorted by key, so JSON key order in the request cannot change the hash.
     * A null and an empty variable map hash identically.
     */
    public static String hash(String channel, String recipient, String templateCode,
                              Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();
        sb.append(channel).append('|');
        sb.append(recipient).append('|');
        sb.append(templateCode).append('|');

        if (variables != null && !variables.isEmpty()) {
            variables.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(','));
        }

        return Hashing.sha256Hex(sb.toString());
    }
}

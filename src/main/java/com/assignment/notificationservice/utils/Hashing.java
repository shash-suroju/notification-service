package com.assignment.notificationservice.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helper shared by API key verification and request hashing. */
public final class Hashing {

    private Hashing() {
        // static utility
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code input} — always 64 chars. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to ship SHA-256.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

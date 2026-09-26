package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.SecurityConstants;
import com.assignment.notificationservice.dtos.ApiKeyAuthentication;
import com.assignment.notificationservice.dtos.ApiKeyCreateResponse;
import com.assignment.notificationservice.dtos.ApiKeyResponse;
import com.assignment.notificationservice.exceptions.EntityNotFoundException;
import com.assignment.notificationservice.models.ApiKey;
import com.assignment.notificationservice.models.enums.ApiKeyStatus;
import com.assignment.notificationservice.repositories.ApiKeyRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import com.assignment.notificationservice.utils.Hashing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies send-API keys.
 *
 * <p>Key format: {@code ntfy_<8-char prefix>_<32-char secret>}. The prefix is stored in clear
 * as an indexed lookup handle; only the SHA-256 of the full key is stored, so a database
 * leak does not leak usable keys.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String KEY_PREFIX = SecurityConstants.API_KEY_PREFIX;
    private static final String CHARS = SecurityConstants.API_KEY_ALPHABET;
    private static final int PREFIX_LENGTH = SecurityConstants.API_KEY_LOOKUP_PREFIX_LENGTH;
    private static final int SECRET_LENGTH = SecurityConstants.API_KEY_SECRET_LENGTH;

    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Issues a key. The returned raw key is the only copy that will ever exist. */
    @Transactional
    public ApiKeyCreateResponse create(UUID tenantId, String name) {
        String prefix = randomString(PREFIX_LENGTH);
        String fullKey = KEY_PREFIX + prefix + "_" + randomString(SECRET_LENGTH);

        ApiKey apiKey = new ApiKey(
                tenantRepository.getReferenceById(tenantId),
                prefix,
                Hashing.sha256Hex(fullKey),
                name,
                clock.instant());
        apiKeyRepository.save(apiKey);

        return new ApiKeyCreateResponse(apiKey.getId(), prefix, fullKey, name, apiKey.getCreatedAt());
    }

    public List<ApiKeyResponse> list(UUID tenantId) {
        return apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(k -> new ApiKeyResponse(k.getId(), k.getPrefix(), k.getName(),
                        k.getStatus(), k.getCreatedAt(), k.getLastUsedAt()))
                .toList();
    }

    /** Soft delete — the row stays for audit, but the key stops authenticating immediately. */
    @Transactional
    public void revoke(UUID tenantId, UUID keyId) {
        ApiKey key = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("ApiKey", keyId));
        key.setStatus(ApiKeyStatus.REVOKED);
        apiKeyRepository.save(key);
    }

    /**
     * Verifies a raw key from the {@code X-API-Key} header. Empty for anything malformed,
     * unknown, revoked or mismatched.
     *
     * <p>A suspended tenant's key still authenticates: suspension is a business rule, enforced
     * by the ingestion service as 403 "tenant suspended". Rejecting it here would surface as
     * an indistinguishable 401 and send the client hunting for a key problem that isn't there.
     */
    @Transactional
    public Optional<ApiKeyAuthentication> authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = rawKey.substring(KEY_PREFIX.length()).split("_", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }

        Optional<ApiKey> found = apiKeyRepository.findByPrefixAndStatus(parts[0], ApiKeyStatus.ACTIVE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey apiKey = found.get();

        // Constant-time compare so response timing does not leak how many hash chars matched.
        byte[] expected = apiKey.getKeyHash().getBytes(StandardCharsets.UTF_8);
        byte[] actual = Hashing.sha256Hex(rawKey).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }

        apiKey.setLastUsedAt(clock.instant());
        apiKeyRepository.save(apiKey);

        return Optional.of(new ApiKeyAuthentication(apiKey.getTenant().getId(), apiKey.getId()));
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(secureRandom.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}

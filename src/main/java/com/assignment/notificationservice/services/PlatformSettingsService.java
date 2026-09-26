package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.PlatformSettingKeys;
import com.assignment.notificationservice.dtos.PlatformSettingsResponse;
import com.assignment.notificationservice.dtos.UpdatePlatformSettingsRequest;
import com.assignment.notificationservice.models.PlatformSetting;
import com.assignment.notificationservice.repositories.PlatformSettingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-wide settings stored as key/value rows. A missing or unparseable row falls back
 * to the documented default rather than failing requests.
 *
 * <p>Lowering {@code maxTenantRatePerSec} does not retroactively change existing tenants;
 * the cap is enforced when a tenant's rate is next set.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlatformSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingsService.class);

    private final PlatformSettingRepository settingRepository;

    public PlatformSettingsResponse getSettings() {
        return new PlatformSettingsResponse(getMaxTenantRatePerSec(), getDefaultMaxAttempts());
    }

    public int getMaxTenantRatePerSec() {
        return getInt(PlatformSettingKeys.MAX_TENANT_RATE_PER_SEC, PlatformSettingKeys.DEFAULT_MAX_TENANT_RATE_PER_SEC);
    }

    public int getDefaultMaxAttempts() {
        return getInt(PlatformSettingKeys.DEFAULT_MAX_ATTEMPTS, PlatformSettingKeys.DEFAULT_DEFAULT_MAX_ATTEMPTS);
    }

    @Transactional
    public PlatformSettingsResponse updateSettings(UpdatePlatformSettingsRequest request) {
        if (request.maxTenantRatePerSec() != null) {
            upsert(PlatformSettingKeys.MAX_TENANT_RATE_PER_SEC, request.maxTenantRatePerSec());
        }
        if (request.defaultMaxAttempts() != null) {
            upsert(PlatformSettingKeys.DEFAULT_MAX_ATTEMPTS, request.defaultMaxAttempts());
        }
        return getSettings();
    }

    private int getInt(String key, int defaultValue) {
        return settingRepository.findById(key)
                .map(s -> {
                    try {
                        return Integer.parseInt(s.getValue().trim());
                    } catch (NumberFormatException e) {
                        log.warn("Platform setting {}='{}' is not an integer; using default {}", key, s.getValue(), defaultValue);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    private void upsert(String key, int value) {
        PlatformSetting setting = settingRepository.findById(key)
                .orElseGet(() -> new PlatformSetting(key, null));
        setting.setValue(String.valueOf(value));
        settingRepository.save(setting);
    }
}

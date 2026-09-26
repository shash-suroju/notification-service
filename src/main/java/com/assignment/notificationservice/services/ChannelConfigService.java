package com.assignment.notificationservice.services;

import com.assignment.notificationservice.dtos.ChannelConfigResponse;
import com.assignment.notificationservice.dtos.UpdateChannelConfigRequest;
import com.assignment.notificationservice.models.ChannelConfig;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.repositories.ChannelConfigRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Per-tenant channel switches. A channel with no config row counts as disabled. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChannelConfigService {

    private static final TypeReference<Map<String, String>> SETTINGS_TYPE = new TypeReference<>() {
    };

    private final ChannelConfigRepository channelConfigRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    /** One entry per channel, always all four; unconfigured channels are reported disabled. */
    public List<ChannelConfigResponse> getAllForTenant(UUID tenantId) {
        Map<Channel, ChannelConfig> byChannel = channelConfigRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(ChannelConfig::getChannel, Function.identity()));

        return Arrays.stream(Channel.values())
                .map(ch -> {
                    ChannelConfig cc = byChannel.get(ch);
                    return cc != null ? toResponse(cc) : new ChannelConfigResponse(null, ch, false, Map.of());
                })
                .toList();
    }

    /** Enables/disables a channel, creating its config row on first use. */
    @Transactional
    public ChannelConfigResponse upsert(UUID tenantId, Channel channel, UpdateChannelConfigRequest request) {
        ChannelConfig config = channelConfigRepository.findByTenantIdAndChannel(tenantId, channel)
                .orElseGet(() -> new ChannelConfig(
                        tenantRepository.getReferenceById(tenantId), channel, request.enabled()));

        config.setEnabled(request.enabled());
        if (request.settings() != null) {
            config.setSettings(serializeSettings(request.settings()));
        }

        return toResponse(channelConfigRepository.save(config));
    }

    /** Used by ingestion to reject sends on a disabled channel. */
    public boolean isChannelEnabled(UUID tenantId, Channel channel) {
        return channelConfigRepository.findByTenantIdAndChannel(tenantId, channel)
                .map(ChannelConfig::isEnabled)
                .orElse(false);
    }

    private ChannelConfigResponse toResponse(ChannelConfig cc) {
        return new ChannelConfigResponse(cc.getId(), cc.getChannel(), cc.isEnabled(),
                deserializeSettings(cc.getSettings()));
    }

    private String serializeSettings(Map<String, String> settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Channel settings are not serialisable", e);
        }
    }

    private Map<String, String> deserializeSettings(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, SETTINGS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored channel settings are not a JSON object", e);
        }
    }
}

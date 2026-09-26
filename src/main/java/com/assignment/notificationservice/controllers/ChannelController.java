package com.assignment.notificationservice.controllers;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.dtos.ChannelConfigResponse;
import com.assignment.notificationservice.dtos.UpdateChannelConfigRequest;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.security.CurrentTenant;
import com.assignment.notificationservice.services.ChannelConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.TENANT_CHANNELS)
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelConfigService channelConfigService;

    @GetMapping
    public List<ChannelConfigResponse> list() {
        return channelConfigService.getAllForTenant(CurrentTenant.resolve());
    }

    /** {@code channel} is the enum name, case-insensitive: EMAIL, SMS, PUSH, IN_APP. */
    @PutMapping("/{channel}")
    public ChannelConfigResponse update(@PathVariable String channel,
                                        @Valid @RequestBody UpdateChannelConfigRequest request) {
        return channelConfigService.upsert(CurrentTenant.resolve(), Channel.fromPath(channel), request);
    }
}

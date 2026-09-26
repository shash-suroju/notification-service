package com.assignment.notificationservice.services;

import com.assignment.notificationservice.dtos.UpdateGlobalLimitRequest;
import com.assignment.notificationservice.exceptions.EntityNotFoundException;
import com.assignment.notificationservice.models.GlobalChannelLimit;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.repositories.GlobalChannelLimitRepository;
import com.assignment.notificationservice.utils.AfterCommit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Platform-wide per-channel rate caps — the second layer of the dispatcher's rate limiting. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GlobalLimitService {

    private final GlobalChannelLimitRepository limitRepository;
    private final RateLimiterRegistry rateLimiterRegistry;

    /** In channel declaration order (EMAIL, SMS, PUSH, IN_APP). */
    public List<GlobalChannelLimit> listAll() {
        return limitRepository.findAll().stream()
                .sorted(Comparator.comparing(GlobalChannelLimit::getChannel))
                .toList();
    }

    public GlobalChannelLimit getByChannel(Channel channel) {
        return limitRepository.findById(channel)
                .orElseThrow(() -> new EntityNotFoundException("GlobalChannelLimit", channel));
    }

    /** Takes effect on the dispatcher's next claim for this channel (the bucket is rebuilt after commit). */
    @Transactional
    public GlobalChannelLimit update(Channel channel, UpdateGlobalLimitRequest request) {
        GlobalChannelLimit limit = getByChannel(channel);
        limit.setRatePerSec(request.ratePerSec());
        limit.setBurst(request.burst());
        GlobalChannelLimit saved = limitRepository.save(limit);

        AfterCommit.run(() -> rateLimiterRegistry.refreshChannel(channel));
        return saved;
    }
}

package com.assignment.notificationservice.controllers;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.constants.EventActors;
import com.assignment.notificationservice.constants.PaginationConstants;
import com.assignment.notificationservice.dtos.NotificationDetailResponse;
import com.assignment.notificationservice.dtos.PageResponse;
import com.assignment.notificationservice.dtos.SendNotificationResponse;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.security.CurrentTenant;
import com.assignment.notificationservice.services.NotificationIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant-admin console view of notifications (HTTP Basic). Kept apart from the send API so
 * each path has exactly one auth method. Cancels here are audited with actor ADMIN.
 */
@RestController
@RequestMapping(ApiPaths.TENANT_NOTIFICATIONS)
@RequiredArgsConstructor
public class TenantNotificationController {

    private final NotificationIngestionService ingestionService;

    @GetMapping
    public PageResponse<SendNotificationResponse> list(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE_SIZE) int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, PaginationConstants.MAX_PAGE_SIZE));
        return PageResponse.from(ingestionService.list(CurrentTenant.resolve(), status, channel, pageable));
    }

    @GetMapping("/{id}")
    public NotificationDetailResponse getById(@PathVariable UUID id) {
        return ingestionService.getDetail(CurrentTenant.resolve(), id);
    }

    @PostMapping("/{id}/cancel")
    public SendNotificationResponse cancel(@PathVariable UUID id) {
        return ingestionService.cancel(CurrentTenant.resolve(), id, EventActors.ADMIN);
    }

    // POST /{id}/retry (FAILED → PENDING) arrives with the retry block (H16-19).
}

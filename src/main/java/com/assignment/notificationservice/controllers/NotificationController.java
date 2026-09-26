package com.assignment.notificationservice.controllers;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.constants.EventActors;
import com.assignment.notificationservice.constants.NotificationConstants;
import com.assignment.notificationservice.constants.PaginationConstants;
import com.assignment.notificationservice.dtos.IngestionResult;
import com.assignment.notificationservice.dtos.NotificationDetailResponse;
import com.assignment.notificationservice.dtos.PageResponse;
import com.assignment.notificationservice.dtos.SendNotificationRequest;
import com.assignment.notificationservice.dtos.SendNotificationResponse;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.security.CurrentTenant;
import com.assignment.notificationservice.services.NotificationIngestionService;
import com.assignment.notificationservice.utils.NotificationMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Send API for tenant backends, authenticated with {@code X-API-Key}.
 * The tenant admin console has its own read/cancel endpoints in {@link TenantNotificationController}.
 */
@RestController
@RequestMapping(ApiPaths.NOTIFICATIONS)
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationIngestionService ingestionService;

    /** 202 Accepted for a new notification; 200 OK when replaying an earlier identical submit. */
    @PostMapping
    public ResponseEntity<SendNotificationResponse> submit(
            @RequestHeader(NotificationConstants.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody SendNotificationRequest request) {

        UUID tenantId = CurrentTenant.resolve();
        validateIdempotencyKey(idempotencyKey);

        IngestionResult result = ingestionService.submit(tenantId, idempotencyKey, request);

        return ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(NotificationMapper.toSendResponse(result.notification()));
    }

    @GetMapping("/{id}")
    public NotificationDetailResponse getById(@PathVariable UUID id) {
        return ingestionService.getDetail(CurrentTenant.resolve(), id);
    }

    @PostMapping("/{id}/cancel")
    public SendNotificationResponse cancel(@PathVariable UUID id) {
        return ingestionService.cancel(CurrentTenant.resolve(), id, EventActors.API);
    }

    @GetMapping
    public PageResponse<SendNotificationResponse> list(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE_SIZE) int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, PaginationConstants.MAX_PAGE_SIZE));
        return PageResponse.from(ingestionService.list(CurrentTenant.resolve(), status, channel, pageable));
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(NotificationConstants.IDEMPOTENCY_KEY_HEADER + " header is required");
        }
        if (key.length() > NotificationConstants.IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(NotificationConstants.IDEMPOTENCY_KEY_HEADER
                    + " must be at most " + NotificationConstants.IDEMPOTENCY_KEY_MAX_LENGTH + " characters");
        }
    }
}

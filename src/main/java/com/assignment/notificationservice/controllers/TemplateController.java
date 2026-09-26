package com.assignment.notificationservice.controllers;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.constants.PaginationConstants;
import com.assignment.notificationservice.dtos.CreateTemplateRequest;
import com.assignment.notificationservice.dtos.PageResponse;
import com.assignment.notificationservice.dtos.TemplatePreviewRequest;
import com.assignment.notificationservice.dtos.TemplatePreviewResponse;
import com.assignment.notificationservice.dtos.TemplateResponse;
import com.assignment.notificationservice.dtos.UpdateTemplateRequest;
import com.assignment.notificationservice.models.Template;
import com.assignment.notificationservice.security.CurrentTenant;
import com.assignment.notificationservice.services.TemplateRenderer;
import com.assignment.notificationservice.services.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Tenant-admin template management. The tenant always comes from the principal. */
@RestController
@RequestMapping(ApiPaths.TENANT_TEMPLATES)
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;
    private final TemplateRenderer renderer;

    @PostMapping
    public ResponseEntity<TemplateResponse> create(@Valid @RequestBody CreateTemplateRequest request) {
        Template created = templateService.create(CurrentTenant.resolve(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping
    public PageResponse<TemplateResponse> list(
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = PaginationConstants.DEFAULT_PAGE_SIZE) int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, PaginationConstants.MAX_PAGE_SIZE));
        return PageResponse.from(
                templateService.list(CurrentTenant.resolve(), pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable UUID id) {
        return toResponse(templateService.get(CurrentTenant.resolve(), id));
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody UpdateTemplateRequest request) {
        return toResponse(templateService.update(CurrentTenant.resolve(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        templateService.deactivate(CurrentTenant.resolve(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/preview")
    public TemplatePreviewResponse preview(@PathVariable UUID id,
                                           @Valid @RequestBody TemplatePreviewRequest request) {
        return templateService.preview(CurrentTenant.resolve(), id, request.variables());
    }

    @GetMapping("/{id}/versions")
    public List<TemplateResponse> versions(@PathVariable UUID id) {
        return templateService.versions(CurrentTenant.resolve(), id).stream()
                .map(this::toResponse)
                .toList();
    }

    private TemplateResponse toResponse(Template t) {
        Set<String> allVars = new LinkedHashSet<>();
        allVars.addAll(renderer.extractVariables(t.getSubject()));
        allVars.addAll(renderer.extractVariables(t.getBody()));
        return new TemplateResponse(
                t.getId(), t.getCode(), t.getChannel(), t.getVersion(),
                t.getSubject(), t.getBody(), t.isActive(), allVars, t.getCreatedAt());
    }
}

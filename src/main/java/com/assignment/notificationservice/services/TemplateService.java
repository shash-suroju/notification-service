package com.assignment.notificationservice.services;

import com.assignment.notificationservice.dtos.CreateTemplateRequest;
import com.assignment.notificationservice.dtos.TemplatePreviewResponse;
import com.assignment.notificationservice.dtos.UpdateTemplateRequest;
import com.assignment.notificationservice.exceptions.ConflictException;
import com.assignment.notificationservice.exceptions.EntityNotFoundException;
import com.assignment.notificationservice.models.Template;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.repositories.TemplateRepository;
import com.assignment.notificationservice.repositories.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Template lifecycle. Templates are never edited in place: an update deactivates the
 * current version and inserts version N+1, so a queued notification's snapshot and the
 * template it came from never disagree.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final TenantRepository tenantRepository;
    private final TemplateRenderer renderer;
    private final Clock clock;

    /** Creates version 1. Fails with 409 if this (code, channel) already exists for the tenant. */
    @Transactional
    public Template create(UUID tenantId, CreateTemplateRequest request) {
        validateSubjectForChannel(request.channel(), request.subject());

        int maxVersion = templateRepository.findMaxVersion(tenantId, request.code(), request.channel());
        if (maxVersion > 0) {
            throw new ConflictException(
                    "Template '" + request.code() + "' for channel " + request.channel()
                            + " already exists. Use PUT to create a new version.");
        }

        Template template = new Template(
                tenantRepository.getReferenceById(tenantId),
                request.code(),
                request.channel(),
                1,
                request.subject(),
                request.body(),
                clock.instant());
        return templateRepository.save(template);
    }

    /** Deactivates {@code templateId} and returns the new, active version N+1. */
    @Transactional
    public Template update(UUID tenantId, UUID templateId, UpdateTemplateRequest request) {
        Template existing = find(tenantId, templateId);

        existing.setActive(false);
        templateRepository.save(existing);

        int nextVersion = templateRepository.findMaxVersion(
                tenantId, existing.getCode(), existing.getChannel()) + 1;

        String subject = request.subject() != null ? request.subject() : existing.getSubject();
        validateSubjectForChannel(existing.getChannel(), subject);

        Template newVersion = new Template(
                existing.getTenant(),
                existing.getCode(),
                existing.getChannel(),
                nextVersion,
                subject,
                request.body() != null ? request.body() : existing.getBody(),
                clock.instant());
        return templateRepository.save(newVersion);
    }

    /** Soft delete: the row stays for audit but can no longer be picked for new sends. */
    @Transactional
    public void deactivate(UUID tenantId, UUID templateId) {
        Template template = find(tenantId, templateId);
        template.setActive(false);
        templateRepository.save(template);
    }

    public Template get(UUID tenantId, UUID templateId) {
        return find(tenantId, templateId);
    }

    public Page<Template> list(UUID tenantId, Pageable pageable) {
        return templateRepository.findLatestByTenantId(tenantId, pageable);
    }

    /** All versions of the template's (code, channel), newest first. */
    public List<Template> versions(UUID tenantId, UUID templateId) {
        Template template = find(tenantId, templateId);
        return templateRepository.findByTenantIdAndCodeAndChannelOrderByVersionDesc(
                tenantId, template.getCode(), template.getChannel());
    }

    /** Renders with sample variables without sending anything. */
    public TemplatePreviewResponse preview(UUID tenantId, UUID templateId,
                                           Map<String, String> sampleVariables) {
        Template template = find(tenantId, templateId);

        String renderedSubject = renderer.render(template.getSubject(), sampleVariables, template.getChannel());
        String renderedBody = renderer.render(template.getBody(), sampleVariables, template.getChannel());

        if (template.getChannel() == Channel.SMS) {
            renderer.validateSmsLength(renderedBody);
        }

        return new TemplatePreviewResponse(
                template.getId(),
                template.getCode(),
                template.getChannel(),
                template.getVersion(),
                renderedSubject,
                renderedBody,
                renderer.extractVariables(template.getBody()),
                renderer.extractVariables(template.getSubject()));
    }

    private Template find(UUID tenantId, UUID templateId) {
        return templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Template", templateId));
    }

    private static void validateSubjectForChannel(Channel channel, String subject) {
        if (channel == Channel.EMAIL && (subject == null || subject.isBlank())) {
            throw new IllegalArgumentException("Subject is required for EMAIL templates");
        }
    }
}

package com.assignment.notificationservice.dtos;

import com.assignment.notificationservice.models.enums.Channel;

import java.util.Set;
import java.util.UUID;

public record TemplatePreviewResponse(
        UUID templateId,
        String code,
        Channel channel,
        int version,
        String renderedSubject,
        String renderedBody,
        Set<String> bodyVariables,
        Set<String> subjectVariables
) {
}

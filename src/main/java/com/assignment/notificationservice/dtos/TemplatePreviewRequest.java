package com.assignment.notificationservice.dtos;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record TemplatePreviewRequest(
        @NotNull Map<String, String> variables
) {
}

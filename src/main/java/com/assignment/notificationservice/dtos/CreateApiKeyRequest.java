package com.assignment.notificationservice.dtos;

import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(
        @Size(max = 100) String name
) {
}

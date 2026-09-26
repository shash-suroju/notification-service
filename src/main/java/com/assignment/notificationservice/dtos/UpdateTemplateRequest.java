package com.assignment.notificationservice.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/** Omitted fields are carried over from the version being replaced. */
public record UpdateTemplateRequest(
        @Size(max = 500) String subject,
        @Size(max = 10000) String body
) {

    @JsonIgnore
    @AssertTrue(message = "At least one of subject or body must be provided")
    public boolean isValid() {
        return subject != null || body != null;
    }
}

package com.assignment.notificationservice.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The password is BCrypt-hashed before storage and never returned. */
public record CreateTenantAdminRequest(
        @NotBlank @Size(min = 3, max = 100)
        @Pattern(regexp = "^[A-Za-z0-9._@-]+$", message = "Username may contain letters, digits and . _ @ - only")
        String username,
        @NotBlank @Size(min = 8, max = 100) String password
) {

    /** Keeps the password out of logs if the request object is ever printed. */
    @Override
    public String toString() {
        return "CreateTenantAdminRequest[username=" + username + ", password=***]";
    }
}

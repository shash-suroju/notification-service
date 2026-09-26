package com.assignment.notificationservice.controllers;

import com.assignment.notificationservice.constants.ApiPaths;
import com.assignment.notificationservice.dtos.ApiKeyCreateResponse;
import com.assignment.notificationservice.dtos.ApiKeyResponse;
import com.assignment.notificationservice.dtos.CreateApiKeyRequest;
import com.assignment.notificationservice.security.CurrentTenant;
import com.assignment.notificationservice.services.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.TENANT_API_KEYS)
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /** The body is optional — a key needs no name. */
    @PostMapping
    public ResponseEntity<ApiKeyCreateResponse> create(
            @Valid @RequestBody(required = false) CreateApiKeyRequest request) {
        String name = request != null ? request.name() : null;
        ApiKeyCreateResponse created = apiKeyService.create(CurrentTenant.resolve(), name);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<ApiKeyResponse> list() {
        return apiKeyService.list(CurrentTenant.resolve());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        apiKeyService.revoke(CurrentTenant.resolve(), id);
        return ResponseEntity.noContent().build();
    }
}

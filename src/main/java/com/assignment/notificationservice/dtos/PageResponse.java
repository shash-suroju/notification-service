package com.assignment.notificationservice.dtos;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable JSON shape for paginated responses. Serialising Spring's {@code PageImpl}
 * directly leaks internal structure that can change between Spring Data versions.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}

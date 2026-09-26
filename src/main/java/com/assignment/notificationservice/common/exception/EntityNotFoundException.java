package com.assignment.notificationservice.common.exception;

/**
 * The requested row does not exist <em>in the caller's tenant view</em>.
 *
 * <p>Deliberately also thrown when a row exists but belongs to another tenant: the API
 * answers 404 rather than 403 so it leaks nothing about other tenants' data.
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String entity, Object id) {
        super(entity + " not found: " + id);
    }
}

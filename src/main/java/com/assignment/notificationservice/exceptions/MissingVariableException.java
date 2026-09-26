package com.assignment.notificationservice.exceptions;

import lombok.Getter;

/** A template placeholder had no value in the supplied variables. Mapped to 400. */
@Getter
public class MissingVariableException extends RuntimeException {

    private final String variableName;

    public MissingVariableException(String variableName) {
        super("Missing required template variable: " + variableName);
        this.variableName = variableName;
    }
}

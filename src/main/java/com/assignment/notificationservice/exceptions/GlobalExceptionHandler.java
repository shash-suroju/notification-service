package com.assignment.notificationservice.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps every exception the API can raise to an RFC 7807 {@link ProblemDetail}, so callers
 * can parse {@code status} and {@code detail} the same way regardless of which endpoint failed.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (unknown
 * route, unreadable body, bad path variable, wrong method) keep their proper 4xx status
 * instead of falling through to the catch-all 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "Not Found");
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "Conflict");
    }

    /** Thrown by NotificationStateMachine for a transition that is not whitelisted. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "Illegal State Transition");
    }

    /** Service-level argument checks, e.g. "subject is required for EMAIL templates". */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "Bad Request");
    }

    @ExceptionHandler(MissingVariableException.class)
    public ProblemDetail handleMissingVariable(MissingVariableException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "Missing Template Variable");
        pd.setProperty("variableName", ex.getVariableName());
        return pd;
    }

    @ExceptionHandler(SmsBodyTooLongException.class)
    public ProblemDetail handleSmsBodyTooLong(SmsBodyTooLongException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "SMS Body Too Long");
    }

    // ---- ingestion ----

    /** The recipient is intentionally not echoed back — it may be personal data. */
    @ExceptionHandler(InvalidRecipientException.class)
    public ProblemDetail handleInvalidRecipient(InvalidRecipientException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "Invalid Recipient");
        pd.setProperty("channel", ex.getChannel());
        return pd;
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "Idempotency Key Conflict");
    }

    @ExceptionHandler(TenantSuspendedException.class)
    public ProblemDetail handleTenantSuspended(TenantSuspendedException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage(), "Tenant Suspended");
    }

    @ExceptionHandler(ChannelDisabledException.class)
    public ProblemDetail handleChannelDisabled(ChannelDisabledException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "Channel Disabled");
        pd.setProperty("channel", ex.getChannel());
        return pd;
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    public ProblemDetail handleTemplateNotFound(TemplateNotFoundException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "Template Not Found");
    }

    @ExceptionHandler(InvalidScheduleTimeException.class)
    public ProblemDetail handleInvalidScheduleTime(InvalidScheduleTimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "Invalid Schedule Time");
    }

    /** Missing required header (e.g. Idempotency-Key) → 400 naming the header. */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing) {
            ProblemDetail pd = problem(HttpStatus.BAD_REQUEST,
                    "Required header '" + missing.getHeaderName() + "' is missing", "Missing Required Header");
            return ResponseEntity.badRequest().body(pd);
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    /** {@code @Valid} failures on request bodies: one entry per field in {@code fieldErrors}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> errors.putIfAbsent(e.getObjectName(), e.getDefaultMessage()));

        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST,
                "Validation failed for " + errors.size() + " field(s)", "Validation Failed");
        pd.setProperty("fieldErrors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = "Request validation failed";
        }
        return problem(HttpStatus.BAD_REQUEST, detail, "Validation Failed");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return problem(HttpStatus.CONFLICT,
                "The request conflicts with existing data", "Conflict");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLocking(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT,
                "The resource was modified concurrently; re-read it and retry",
                "Concurrent Modification");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", "Forbidden");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred", "Internal Server Error");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String title) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                status, detail == null ? status.getReasonPhrase() : detail);
        pd.setTitle(title);
        return pd;
    }

    private static String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}

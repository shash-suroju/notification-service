package com.assignment.notificationservice.unit;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assignment.notificationservice.common.GlobalExceptionHandler;
import com.assignment.notificationservice.common.exception.ConflictException;
import com.assignment.notificationservice.common.exception.EntityNotFoundException;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the advice emits RFC 7807 for every exception it claims to handle.
 * Standalone MockMvc keeps this a pure unit test — no Spring context, no database.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void entityNotFoundBecomes404ProblemDetail() throws Exception {
        mockMvc.perform(get("/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Tenant not found: 42"))
                .andExpect(jsonPath("$.instance").value("/boom/not-found"));
    }

    @Test
    void conflictBecomes409() throws Exception {
        mockMvc.perform(get("/boom/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("slug already taken"));
    }

    @Test
    void illegalStateFromTheStateMachineBecomes409() throws Exception {
        mockMvc.perform(get("/boom/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal State Transition"))
                .andExpect(jsonPath("$.detail").value("Illegal transition: SENT → PROCESSING"));
    }

    @Test
    void bodyValidationFailureBecomes400WithFieldNames() throws Exception {
        mockMvc.perform(post("/boom/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("slug")));
    }

    @Test
    void constraintViolationBecomes400() throws Exception {
        mockMvc.perform(get("/boom/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));
    }

    @Test
    void dataIntegrityViolationBecomes409WithoutLeakingSql() throws Exception {
        mockMvc.perform(get("/boom/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"));
    }

    @Test
    void optimisticLockFailureBecomes409() throws Exception {
        mockMvc.perform(get("/boom/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent Modification"));
    }

    @Test
    void accessDeniedBecomes403() throws Exception {
        mockMvc.perform(get("/boom/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void unexpectedExceptionBecomes500WithoutLeakingTheMessage() throws Exception {
        mockMvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    record SlugRequest(@NotBlank String slug) {
    }

    @RestController
    @RequestMapping("/boom")
    static class BoomController {

        @GetMapping("/not-found")
        void notFound() {
            throw new EntityNotFoundException("Tenant", 42);
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("slug already taken");
        }

        @GetMapping("/illegal-state")
        void illegalState() {
            throw new IllegalStateException("Illegal transition: SENT → PROCESSING");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody SlugRequest request) {
            // never reached in this test
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new ConstraintViolationException("invalid", Set.of());
        }

        @GetMapping("/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uq_tenant_slug\"");
        }

        @GetMapping("/optimistic")
        void optimistic() {
            throw new OptimisticLockingFailureException("row version mismatch");
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new RuntimeException("internal detail that must not leak");
        }
    }
}

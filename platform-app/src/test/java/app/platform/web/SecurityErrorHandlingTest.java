package app.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.logging.LogContext;
import app.platform.tenant.TenantContexts;
import app.platform.web.error.ErrorResponses;
import app.platform.web.error.GlobalExceptionHandler;
import app.platformapi.ApiException;
import app.webtest.IdentityTestController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The carry-over from Sprint 1: the security exceptions are answered in the error model (401 {@code UNAUTHENTICATED},
 * 403 {@code FORBIDDEN}) and never become a 500, whether thrown inside a controller or handed over by the security
 * filter chain, and the exception's own text is never forwarded. Also the {@code Retry-After} header of a rate limit.
 */
class SecurityErrorHandlingTest {

    /** Raises the refusals that carry advice for the caller. */
    @RestController
    static class AdviceController {

        @GetMapping("/api/v1/test/limited")
        String limited() {
            throw ApiException.rateLimited(42);
        }

        @GetMapping("/api/v1/test/unavailable")
        String unavailable() {
            throw ApiException.unavailable(7);
        }
    }

    private MockMvc mvc;
    private LogContext.Scope requestScope;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new ErrorResponses(meters),
                new ErrorTracker(List.of(report -> { }), meters));
        mvc = MockMvcBuilders.standaloneSetup(new IdentityTestController(new TenantContexts()),
                        new AdviceController())
                .setControllerAdvice(handler).build();
        requestScope = LogContext.with(LogContext.REQUEST_ID, "req_unit_test_2");
    }

    @AfterEach
    void tearDown() {
        requestScope.close();
    }

    @Test
    void anAuthenticationExceptionIsA401InTheErrorModelWithABearerChallengeAndNoDetail() throws Exception {
        mvc.perform(get("/api/v1/test/security/unauthenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required."))
                .andExpect(jsonPath("$.error.requestId").value("req_unit_test_2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET-INTERNAL"))));
    }

    @Test
    void anAccessDeniedExceptionIsA403InTheErrorModelWithNoDetail() throws Exception {
        mvc.perform(get("/api/v1/test/security/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("You are not allowed to perform this action."))
                .andExpect(jsonPath("$.error.requestId").value("req_unit_test_2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET-INTERNAL"))));
    }

    @Test
    void aRateLimitCarriesItsRetryAfterHeader() throws Exception {
        mvc.perform(get("/api/v1/test/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void anUnavailableDependencyCarriesItsRetryAfterHeader() throws Exception {
        mvc.perform(get("/api/v1/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }
}

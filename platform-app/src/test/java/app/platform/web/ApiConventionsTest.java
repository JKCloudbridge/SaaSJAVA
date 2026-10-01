package app.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.logging.LogContext;
import app.platform.web.error.ErrorResponses;
import app.platform.web.error.GlobalExceptionHandler;
import app.platform.web.pagination.PageRequestArgumentResolver;
import app.platformapi.Cursors;
import app.webtest.ConventionsTestController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * The conventions of the API (ADR-0011) against the real error handler, paging binding and a controller that
 * misbehaves in every way a controller can: envelope, paging, validation, every failure kind, and the promise that
 * no failure reveals internals.
 */
class ApiConventionsTest {

    private static final String REQUEST_ID = "req_unit_test_1";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ErrorReport> reported = new ArrayList<>();
    private LogContext.Scope requestScope;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new ErrorResponses(meters), new ErrorTracker(List.of(reported::add), meters));
        mvc = MockMvcBuilders.standaloneSetup(new ConventionsTestController())
                .setControllerAdvice(handler)
                .setCustomArgumentResolvers(new PageRequestArgumentResolver(validator))
                .setValidator(validator)
                .build();
        requestScope = LogContext.with(LogContext.REQUEST_ID, REQUEST_ID);
    }

    @AfterEach
    void tearDown() {
        requestScope.close();
    }

    // ---- envelope and paging ----

    @Test
    void aSingleResultIsWrappedInData() throws Exception {
        mvc.perform(get("/api/v1/test/item"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("item-1"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void aCollectionCarriesItsPaginationAndDefaultsTheLimit() throws Exception {
        mvc.perform(get("/api/v1/test/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.pagination.limit").value(50))
                .andExpect(jsonPath("$.pagination.hasMore").value(false))
                .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist());
    }

    @Test
    void followingTheCursorWalksThroughEveryItemExactlyOnce() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/api/v1/test/items").param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
            String body = result.getResponse().getContentAsString();
            com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(body);
            seen.addAll(json.read("$.data[*].id"));
            cursor = json.read("$.pagination.nextCursor", String.class);
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactly("item-0", "item-1", "item-2", "item-3", "item-4");
        assertThat(pages).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "201", "9999999999", "abc", "1.5"})
    void anInvalidLimitIsAValidationErrorNamingTheParameter(String limit) throws Exception {
        mvc.perform(get("/api/v1/test/items").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.limit").isNotEmpty())
                .andExpect(jsonPath("$.error.requestId").value(REQUEST_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a cursor!", "%%%", "***"})
    void anInvalidCursorIsAValidationError(String cursor) throws Exception {
        mvc.perform(get("/api/v1/test/items").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.cursor").isNotEmpty());
    }

    @Test
    void anOversizedCursorIsRejected() throws Exception {
        mvc.perform(get("/api/v1/test/items").param("cursor", Cursors.encode("x".repeat(600))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.cursor").isNotEmpty());
    }

    // ---- validation ----

    @Test
    void bodyValidationListsEveryInvalidFieldAndNeverEchoesTheValues() throws Exception {
        String rejected = "{\"name\":\"  \",\"quantity\":0}";

        MvcResult result = mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON)
                        .content(rejected))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.name").isNotEmpty())
                .andExpect(jsonPath("$.error.fields.quantity").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"  \"");
    }

    @Test
    void aParameterConstraintIsAValidationError() throws Exception {
        mvc.perform(get("/api/v1/test/param").param("n", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.n").isNotEmpty());
    }

    @Test
    void aParameterOfTheWrongTypeIsAValidationErrorWithoutConversionDetails() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test/param").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.n[0]").value("Has an invalid format."))
                .andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Test
    void aMissingParameterIsAValidationError() throws Exception {
        mvc.perform(get("/api/v1/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.n").isNotEmpty());
    }

    @Test
    void anUnreadableBodyIsAMalformedRequestWithoutParserDetails() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": <not json> SECRET-INTERNAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Test
    void aMissingBodyIsAMalformedRequest() throws Exception {
        mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    // ---- framework failures ----

    @Test
    void anUnsupportedMethodIs405WithTheAllowHeaderKept() throws Exception {
        mvc.perform(post("/api/v1/test/item"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                        "Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void anUnsupportedContentTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/test/items").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void anUnknownPathIs404InTheSameModel() throws Exception {
        mvc.perform(get("/api/v1/test/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.requestId").value(REQUEST_ID));
    }

    // ---- failures raised by modules and by the data layer ----

    @Test
    void anApiExceptionKeepsItsCodeAndItsClientFacingMessage() throws Exception {
        mvc.perform(get("/api/v1/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("The item was not found."));
        assertThat(reported).as("expected failures are not incidents").isEmpty();
    }

    @Test
    void aConcurrentModificationIs409WithItsOwnCode() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test/concurrent"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"))
                .andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
        assertThat(reported).isEmpty();
    }

    @Test
    void anUnavailableDependencyIs503AndIsReported() throws Exception {
        mvc.perform(get("/api/v1/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        assertThat(reported).hasSize(1);
    }

    @Test
    void aDatabaseThatCannotBeReachedIs503WithoutConnectionDetails() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test/database-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
        assertThat(reported).hasSize(1);
    }

    // ---- unexpected failures never leak ----

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/test/boom", "/api/v1/test/sql"})
    void anUnexpectedFailureIsAGenericInternalErrorAndIsReported(String path) throws Exception {
        MvcResult result = mvc.perform(get(path))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.error.requestId").value(REQUEST_ID))
                .andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0).requestId()).isEqualTo(REQUEST_ID);
        assertThat(reported.get(0).path()).isEqualTo(path);
        assertThat(meters.get("platform.api.errors").tag("code", "INTERNAL_ERROR").counter().count()).isEqualTo(1.0);
    }

    @Test
    void everyErrorBodyHasExactlyTheDocumentedShape() throws Exception {
        Set<String> allowed = Set.of("code", "message", "fields", "requestId", "traceId");
        for (String path : List.of("/api/v1/test/boom", "/api/v1/test/not-found", "/api/v1/test/nothing",
                "/api/v1/test/param")) {
            String body = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
            com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(body);

            assertThat(json.<java.util.Map<String, Object>>read("$").keySet()).containsExactly("error");
            assertThat(json.<java.util.Map<String, Object>>read("$.error").keySet()).isSubsetOf(allowed);
        }
    }

    /** Nothing an exception, a driver or a framework says may appear in a response. */
    private static void assertNoInternals(String body) {
        assertThat(body)
                .doesNotContain("SECRET-INTERNAL", "hunter2", "jdbc", "postgresql", "db.internal")
                .doesNotContain("user-a@example.test")
                .doesNotContain("Exception", "java.", "org.springframework", "tools.jackson", "com.fasterxml")
                .doesNotContain("at app.", "stackTrace", "trace\":", "SQL", "23505", "Key (");
    }
}

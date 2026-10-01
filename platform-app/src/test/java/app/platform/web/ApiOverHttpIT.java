package app.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestHttp;
import app.platformapi.ApiHeaders;
import app.webtest.ConventionsTestController;
import app.webtest.DatabaseTestController;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The API conventions on the real stack: a real HTTP server, the real filters and error handling, a real database.
 * Complements {@link ApiConventionsTest}, which covers the same rules at unit level.
 */
@PlatformIntegrationTest
@Import({ConventionsTestController.class, DatabaseTestController.class})
class ApiOverHttpIT {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private Users users;

    private String bearer;

    /** The API as a signed-in caller: everything outside the public list needs one since Sprint 3. */
    private TestHttp api() {
        if (bearer == null) {
            bearer = TestSignIn.bearerOnPlatformHost(apiPort, users);
        }
        return new TestHttp(apiPort, "Authorization", bearer);
    }

    // ---- the status endpoint ----

    @Test
    void statusAnswersInTheEnvelopeAndProvesTheDatabaseResponded() {
        TestHttp.Response response = api().get("/api/v1/platform/status");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).contains("json"));
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        assertThat(body).containsOnlyKeys("data");
        Map<String, Object> data = JsonPath.parse(response.body()).read("$.data");
        assertThat(data).containsOnlyKeys("service", "apiVersion", "serverTime", "databaseTime");
        assertThat(data).containsEntry("service", "platform").containsEntry("apiVersion", "v1");
        Instant databaseTime = Instant.parse((String) data.get("databaseTime"));
        assertThat(Duration.between(databaseTime, Instant.now()).abs()).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void statusRevealsNoDeploymentDetails() {
        String body = api().get("/api/v1/platform/status").body();

        assertThat(body).doesNotContain("jdbc", "postgres", "localhost", "password", "version\":\"0.", "java");
    }

    // ---- request ID and trace ID ----

    @Test
    void everyResponseCarriesAGeneratedRequestIdAndATraceId() {
        TestHttp.Response response = api().get("/api/v1/platform/status");

        assertThat(response.header(ApiHeaders.REQUEST_ID)).hasValueSatisfying(
                id -> assertThat(id).matches("req_[0-9A-HJKMNP-TV-Z]{22}"));
        assertThat(response.header(ApiHeaders.TRACE_ID)).hasValueSatisfying(
                id -> assertThat(id).matches("[0-9a-f]{32}"));
    }

    @Test
    void aWellFormedClientRequestIdIsKeptAndEchoed() {
        TestHttp.Response response = api().get("/api/v1/platform/status", ApiHeaders.REQUEST_ID, "client-req-0001");

        assertThat(response.header(ApiHeaders.REQUEST_ID)).contains("client-req-0001");
    }

    @Test
    void anUnsafeClientRequestIdIsReplacedNotEchoed() {
        TestHttp.Response response = api().get(
                "/api/v1/platform/status", ApiHeaders.REQUEST_ID, "bad id with spaces <b>");

        assertThat(response.header(ApiHeaders.REQUEST_ID)).hasValueSatisfying(
                id -> assertThat(id).startsWith("req_").doesNotContain(" ", "<"));
    }

    @Test
    void anIncomingTraceContextIsContinuedNotReplaced() {
        TestHttp.Response response = api().get("/api/v1/platform/status", "traceparent", TRACEPARENT);

        assertThat(response.header(ApiHeaders.TRACE_ID)).contains(TRACE_ID);
    }

    @Test
    void errorBodiesCarryTheSameIdsAsTheHeaders() {
        TestHttp.Response response = api().get("/api/v1/test/boom", "traceparent", TRACEPARENT,
                ApiHeaders.REQUEST_ID, "client-req-0002");

        assertThat(response.status()).isEqualTo(500);
        assertThat((String) JsonPath.read(response.body(), "$.error.requestId")).isEqualTo("client-req-0002");
        assertThat((String) JsonPath.read(response.body(), "$.error.traceId")).isEqualTo(TRACE_ID);
        assertThat(response.header(ApiHeaders.REQUEST_ID)).contains("client-req-0002");
    }

    // ---- error model over real HTTP ----

    @Test
    void anUnknownPathAnswersInTheErrorModel() {
        TestHttp.Response response = api().get("/api/v1/no-such-thing");

        assertThat(response.status()).isEqualTo(404);
        assertThat((String) JsonPath.read(response.body(), "$.error.code")).isEqualTo("NOT_FOUND");
        assertThat(response.body()).doesNotContain("null", "<html", "Whitelabel", "timestamp", "path");
        assertThat(response.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).contains("json"));
    }

    @Test
    void aFailureEvenWithAnUnacceptableAcceptHeaderIsStillJson() {
        TestHttp.Response response = api().get("/api/v1/test/item/missing", "Accept", "application/xml");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).contains("json"));
        assertThat((String) JsonPath.read(response.body(), "$.error.code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformedAndInvalidBodiesAnswerWithTheirOwnCodes() {
        String json = "application/json";
        TestHttp.Response malformed = api().post("/api/v1/test/items", "{ not json", "Content-Type", json);
        TestHttp.Response invalid = api().post("/api/v1/test/items", "{\"name\":\"\"}", "Content-Type", json);

        assertThat(malformed.status()).isEqualTo(400);
        assertThat((String) JsonPath.read(malformed.body(), "$.error.code")).isEqualTo("MALFORMED_REQUEST");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat((String) JsonPath.read(invalid.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<Map<String, List<String>>>read(invalid.body(), "$.error.fields")).containsKey("name");
    }

    @Test
    void anUnexpectedFailureLeaksNothingOverTheWire() {
        for (String path : List.of("/api/v1/test/boom", "/api/v1/test/sql", "/api/v1/test/database-down")) {
            TestHttp.Response response = api().get(path);

            assertThat(response.body()).as(path)
                    .doesNotContain("SECRET-INTERNAL", "hunter2", "jdbc", "db.internal", "user-a@example.test")
                    .doesNotContain("Exception", "java.", "org.", "at app.", "23505");
            assertThat(response.headers().toString()).as(path).doesNotContain("SECRET-INTERNAL", "Exception");
        }
    }

    @Test
    void pagingWorksOverHttp() {
        TestHttp.Response first = api().get("/api/v1/test/items?limit=3");
        String cursor = JsonPath.read(first.body(), "$.pagination.nextCursor");
        TestHttp.Response second = api().get("/api/v1/test/items?limit=3&cursor=" + cursor);

        assertThat(JsonPath.<List<String>>read(first.body(), "$.data[*].id"))
                .containsExactly("item-0", "item-1", "item-2");
        assertThat(JsonPath.<List<String>>read(second.body(), "$.data[*].id")).containsExactly("item-3", "item-4");
        assertThat(second.body()).doesNotContain("nextCursor");
        assertThat(api().get("/api/v1/test/items?limit=500").status()).isEqualTo(400);
    }

    // ---- management port ----

    @Test
    void metricsAreScrapedFromTheManagementPortAndCountErrorsByCode() {
        api().get("/api/v1/test/boom");
        api().get("/api/v1/test/not-found");

        String metrics = new TestHttp(managementPort).get("/actuator/prometheus").body();

        assertThat(metrics)
                .contains("http_server_requests_seconds_count")
                .contains("platform_api_errors_total{application=\"platform\",code=\"INTERNAL_ERROR\"")
                .contains("platform_api_errors_total{application=\"platform\",code=\"NOT_FOUND\"")
                .contains("platform_errors_unhandled_total")
                .contains("hikaricp_connections");
    }

    @Test
    void metricsAreNotServedOnTheApiPort() {
        assertThat(api().get("/actuator/prometheus").status()).isEqualTo(404);
    }

    @Test
    void theErrorPathAnswersInTheErrorModelToo() {
        TestHttp.Response response = api().get("/error");

        assertThat((String) JsonPath.read(response.body(), "$.error.code")).isEqualTo("NOT_FOUND");
    }
}

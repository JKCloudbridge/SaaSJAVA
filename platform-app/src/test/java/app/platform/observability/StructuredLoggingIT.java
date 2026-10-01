package app.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.logging.LogContext;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.identity.Users;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platformapi.ApiHeaders;
import app.platformapi.ApiPaths;
import app.webtest.ConventionsTestController;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Structured JSON logging in the form a deployment produces: one JSON object per line, carrying the request ID and
 * trace ID of the request (and the tenant ID when one is set), an access record per request, and an error record
 * that names the failure without quoting what caused it.
 */
@PlatformIntegrationTest
@Import(ConventionsTestController.class)
@TestPropertySource(properties = {
    "logging.structured.format.file=ecs",
    "logging.file.name=" + StructuredLoggingIT.LOG_FILE})
class StructuredLoggingIT {

    static final String LOG_FILE = "target/it-logs/structured.log";
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private String bearer;

    /** A caller signed in on the platform host; the test endpoints need one since Sprint 3. */
    private TestHttp signedIn() {
        if (bearer == null) {
            bearer = TestSignIn.bearerOnPlatformHost(port, users);
        }
        return new TestHttp(port, "Authorization", bearer);
    }

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @Test
    void theAccessRecordCarriesTheRequestIdAndTraceId() throws IOException {
        new TestHttp(port).get("/api/v1/platform/status", ApiHeaders.REQUEST_ID, "client-req-log-1",
                "traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");

        Map<String, Object> record = lineWhere("$.requestId", "client-req-log-1", "$.message", "request completed");

        assertThat(record).containsEntry("message", "request completed")
                .containsEntry("requestId", "client-req-log-1")
                .containsEntry("traceId", TRACE_ID)
                .containsEntry("http_method", "GET")
                .containsEntry("http_path", "/api/v1/platform/status")
                .containsEntry("http_status", 200)
                .containsKey("duration_ms")
                .containsKey("@timestamp");
        assertThat(JsonPath.<String>read(record, "$.log.level")).isEqualTo("INFO");
        assertThat(JsonPath.<String>read(record, "$.log.logger")).isEqualTo("platform.http");
        assertThat(record).as("a platform host has no tenant").doesNotContainKey("tenantId");
    }

    @Test
    void anUnexpectedErrorIsLoggedAsAStructuredRecordWithoutTheQuotedData() throws IOException {
        signedIn().get("/api/v1/test/sql", ApiHeaders.REQUEST_ID, "client-req-log-2",
                "traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");

        Map<String, Object> record = lineWhere("$.log.logger", "platform.error", "$.requestId", "client-req-log-2");

        assertThat(record).containsEntry("requestId", "client-req-log-2")
                .containsEntry("traceId", TRACE_ID)
                .containsEntry("http_path", "/api/v1/test/sql")
                .containsEntry("error_sql_state", "23505");
        assertThat(JsonPath.<String>read(record, "$.error_type"))
                .isEqualTo("org.springframework.dao.DataIntegrityViolationException");
        assertThat(JsonPath.<String>read(record, "$.error_stack")).contains("ConventionsTestController.sql");
        assertThat(JsonPath.<String>read(record, "$.log.level")).isEqualTo("ERROR");

        String everything = Files.readString(Path.of(LOG_FILE), StandardCharsets.UTF_8);
        assertThat(everything).as("what the failure quoted must not be in any log line")
                .doesNotContain("SECRET-INTERNAL").doesNotContain("hunter2").doesNotContain("user-a@example.test");
    }

    @Test
    void theTenantIdAppearsOnEveryLineWhileItIsSet() throws IOException {
        Logger logger = LoggerFactory.getLogger("structured.logging.it");

        LogContext.Scope scope = LogContext.with(LogContext.TENANT_ID, "tenant-a");
        try {
            logger.info("inside the tenant scope marker-tenant-1");
        } finally {
            scope.close();
        }
        logger.info("outside the tenant scope marker-tenant-2");

        assertThat(lineContaining("marker-tenant-1")).containsEntry("tenantId", "tenant-a");
        assertThat(lineContaining("marker-tenant-2")).doesNotContainKey("tenantId");
    }

    @Test
    void everyLineOfARequestToAnOrganizationHostCarriesItsTenantIncludingTheAccessRecord() throws IOException {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        new TestHttp(port).get(ApiPaths.TENANT_CURRENT, ApiHeaders.REQUEST_ID, "client-req-log-tenant",
                "Host", tenant.host());

        Map<String, Object> access = lineWhere("$.requestId", "client-req-log-tenant", "$.message",
                "request completed");
        assertThat(access).containsEntry("tenantId", tenant.id().toString()).containsEntry("http_status", 200);
    }

    @Test
    void aRefusedHostIsLoggedWithoutATenantAndWithoutTheHostName() throws IOException {
        new TestHttp(port).get(ApiPaths.TENANT_CURRENT, ApiHeaders.REQUEST_ID, "client-req-log-refused",
                "Host", "no-such-organization.platform.example.test");

        Map<String, Object> access = lineWhere("$.requestId", "client-req-log-refused", "$.message",
                "request completed");
        assertThat(access).containsEntry("http_status", 404).doesNotContainKey("tenantId");
        assertThat(Files.readString(Path.of(LOG_FILE), StandardCharsets.UTF_8))
                .as("the host name is client input and is not logged").doesNotContain("no-such-organization");
    }

    @Test
    void everyLineOfTheFileIsAJsonObjectWithTheBasicFields() throws IOException {
        new TestHttp(port).get("/api/v1/platform/status");

        List<String> lines = Files.readAllLines(Path.of(LOG_FILE), StandardCharsets.UTF_8);

        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            Map<String, Object> json = JsonPath.parse(line).read("$");
            assertThat(json).as(line).containsKeys("@timestamp", "message", "log");
        }
    }

    private static Map<String, Object> lineWhere(String... pathsAndValues) throws IOException {
        for (String line : Files.readAllLines(Path.of(LOG_FILE), StandardCharsets.UTF_8)) {
            var json = JsonPath.parse(line);
            boolean matches = true;
            for (int i = 0; i + 1 < pathsAndValues.length; i += 2) {
                Object actual = readOrNull(json, pathsAndValues[i]);
                matches &= pathsAndValues[i + 1].equals(actual);
            }
            if (matches) {
                return json.read("$");
            }
        }
        throw new AssertionError("no log line matched " + List.of(pathsAndValues));
    }

    private static Map<String, Object> lineContaining(String marker) throws IOException {
        for (String line : Files.readAllLines(Path.of(LOG_FILE), StandardCharsets.UTF_8)) {
            if (line.contains(marker)) {
                return JsonPath.parse(line).read("$");
            }
        }
        throw new AssertionError("no log line contains " + marker);
    }

    private static Object readOrNull(com.jayway.jsonpath.DocumentContext json, String path) {
        try {
            return json.read(path);
        } catch (com.jayway.jsonpath.PathNotFoundException e) {
            return null;
        }
    }
}

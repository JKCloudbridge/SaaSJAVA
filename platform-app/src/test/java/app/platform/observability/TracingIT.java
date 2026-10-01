package app.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp;
import app.platformapi.ApiHeaders;
import app.webtest.ConventionsTestController;
import app.webtest.DatabaseTestController;
import com.jayway.jsonpath.JsonPath;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * One trace spans the incoming request, the application's work and the database (exit criterion of Sprint 1): the
 * trace the caller started is continued, each SQL statement is a child span in the same trace, bind values never
 * reach a span, and the database connection itself is stamped with the trace ID of the transaction.
 */
@PlatformIntegrationTest
@Import({TracingIT.Spans.class, ConventionsTestController.class, DatabaseTestController.class})
class TracingIT {

    private static final AttributeKey<String> REQUEST_ID_ATTRIBUTE = AttributeKey.stringKey("request.id");

    /** Collects finished spans in memory. */
    @TestConfiguration
    static class Spans {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SpanProcessor spanProcessor(InMemorySpanExporter exporter) {
            return SimpleSpanProcessor.create(exporter);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private InMemorySpanExporter exporter;

    @BeforeEach
    void clearSpans() {
        exporter.reset();
    }

    @Test
    void theCallersTraceIsContinuedThroughTheApplicationToTheDatabase() {
        String traceId = newTraceId();
        String parentSpanId = "00f067aa0ba902b7";

        TestHttp.Response response = new TestHttp(port).get("/api/v1/platform/status",
                "traceparent", "00-" + traceId + "-" + parentSpanId + "-01",
                ApiHeaders.REQUEST_ID, "client-req-trace1");

        assertThat(response.status()).isEqualTo(200);
        List<SpanData> spans = spansOf(traceId);
        SpanData server = spans.stream().filter(span -> span.getName().startsWith("http get")).findFirst()
                .orElseThrow(() -> new AssertionError("no server span in " + names(spans)));
        assertThat(server.getParentSpanId()).as("the caller's span is the parent").isEqualTo(parentSpanId);
        assertThat(server.getAttributes().get(REQUEST_ID_ATTRIBUTE)).isEqualTo("client-req-trace1");

        List<SpanData> queries = spans.stream().filter(span -> span.getName().equals("query")).toList();
        assertThat(queries).as("database statements are spans of the same trace").hasSizeGreaterThanOrEqualTo(2);
        assertThat(queries).allSatisfy(query -> assertThat(query.getParentSpanId()).isEqualTo(server.getSpanId()));
        assertThat(queries.stream().map(TracingIT::statement))
                .anyMatch(statement -> statement.contains("select now()"));
        assertThat(response.header(ApiHeaders.TRACE_ID)).contains(traceId);
    }

    @Test
    void bindValuesNeverReachASpan() {
        String traceId = newTraceId();
        String secret = "SECRET-BIND-VALUE-user-a@example.test";

        new TestHttp(port).get("/api/v1/test/db/bind?value=" + secret, "traceparent",
                "00-" + traceId + "-00f067aa0ba902b7-01");

        List<SpanData> spans = spansOf(traceId);
        assertThat(spans.stream().filter(span -> span.getName().equals("query"))).isNotEmpty();
        assertThat(spans.toString()).doesNotContain(secret).doesNotContain("SECRET-BIND-VALUE");
    }

    @Test
    void theDatabaseConnectionIsStampedWithTheTraceAndRequestOfTheTransaction() {
        String traceId = newTraceId();

        TestHttp.Response response = new TestHttp(port).get("/api/v1/test/db/application-name",
                "traceparent", "00-" + traceId + "-00f067aa0ba902b7-01", ApiHeaders.REQUEST_ID, "client-req-stamp1");

        assertThat(JsonPath.<String>read(response.body(), "$.data[0]"))
                .as("inside the transaction")
                .isEqualTo("t=" + traceId + " r=client-req-stamp1");
        assertThat(JsonPath.<String>read(response.body(), "$.data[1]"))
                .as("outside a transaction the pooled connection is back to its default name")
                .isEqualTo("platform");
    }

    @Test
    void concurrentRequestsNeverSeeEachOthersIdentifiersOnPooledConnections() throws Exception {
        int requests = 120;
        try (ExecutorService pool = Executors.newFixedThreadPool(24)) {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String traceId = newTraceId();
                String requestId = "client-req-" + String.format("%04d", i);
                calls.add(() -> {
                    TestHttp.Response response = new TestHttp(port).get("/api/v1/test/db/application-name",
                            "traceparent", "00-" + traceId + "-00f067aa0ba902b7-01", ApiHeaders.REQUEST_ID, requestId);
                    String inside = JsonPath.read(response.body(), "$.data[0]");
                    String outside = JsonPath.read(response.body(), "$.data[1]");
                    boolean correct = inside.equals("t=" + traceId + " r=" + requestId) && outside.equals("platform");
                    return correct ? "ok" : "WRONG " + requestId + " saw " + inside + " / " + outside;
                });
            }
            List<String> results = new ArrayList<>();
            for (Future<String> future : pool.invokeAll(calls)) {
                results.add(future.get());
            }

            assertThat(results).hasSize(requests).allMatch("ok"::equals);
        }
    }

    private List<SpanData> spansOf(String traceId) {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }

    private static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }

    private static String statement(SpanData span) {
        Object value = span.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().startsWith("jdbc.query"))
                .map(entry -> entry.getValue())
                .findFirst()
                .orElse("");
        return value.toString();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

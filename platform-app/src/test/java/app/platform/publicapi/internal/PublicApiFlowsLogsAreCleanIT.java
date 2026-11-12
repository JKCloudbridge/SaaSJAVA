package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Nothing a caller of the public API typed, no token and no idempotency key is logged, audited, announced, kept in the
 * memory of keyed writes, used as a tag of a metric or echoed in an answer that is not the record itself (Sprint 30,
 * ADR-0012, ADR-0246, ADR-0247): a canary is put into a field value, a filter, a field name, a token and a key, through
 * every operation, and looked for in every place a trace could be left.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + PublicApiFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"})
class PublicApiFlowsLogsAreCleanIT extends PublicApiSupport {

    static final String LOG_FILE = "target/it-logs/public-api-clean.log";

    @Autowired
    private MeterRegistry meters;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String canary(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    @Test
    void noValueFilterNameKeyOrTokenOfThePublicApiIsLoggedStoredCountedOrEchoed() throws IOException {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        String value = canary("value");
        String filter = canary("filter");
        String field = canary("field");
        String key = canary("idempotency-key");
        String bulkValue = canary("bulk-value");
        String refused = canary("refused");
        String token = program.token();

        List<Response> answers = new ArrayList<>();
        Response created = program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + value + "\"}"),
                "Idempotency-Key", key);
        answers.add(created);
        answers.add(program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + value + "\"}"),
                "Idempotency-Key", key));
        answers.add(program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + refused + "\"}"),
                "Idempotency-Key", key));
        answers.add(program.post(EMPLOYEE_RECORDS, createBody("{\"" + field + "\":1,\"salary__c\":\"" + refused
                + "\"}")));
        answers.add(program.post(EMPLOYEE_RECORDS + "/query", "{\"filter\":{\"op\":\"EQ\",\"field\":\"name__c\","
                + "\"values\":[\"" + filter + "\"]}}"));
        answers.add(program.post(EMPLOYEE_RECORDS + "/query", "{\"filter\":{\"op\":\"EQ\",\"field\":\"" + field
                + "\",\"values\":[\"" + filter + "\"]}}"));
        answers.add(program.post(EMPLOYEE_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"records\":["
                + "{\"fields\":{\"name__c\":\"" + bulkValue + "\"}},{\"fields\":{\"salary__c\":\"" + refused
                + "\"}}]}", "Idempotency-Key", canary("bulk-key")));
        answers.add(program.patch(EMPLOYEE_RECORDS + "/" + id(created), "{\"fields\":{\"" + field + "\":1}}",
                "If-Match", "\"0\""));
        answers.add(program.get(EMPLOYEE_RECORDS + "/" + id(created)));
        answers.add(program.delete(EMPLOYEE_RECORDS + "/" + id(created)));
        answers.add(program.post(EMPLOYEE_RECORDS + "/" + id(created) + "/restore", ""));
        answers.add(program.withToken(token + "x").get(OBJECTS + "/" + field));
        String trail = program.browser().get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        for (String secret : List.of(value, filter, field, key, bulkValue, refused, token)) {
            assertThat(log).as("the log: " + secret).doesNotContain(secret);
            assertThat(trail).as("the audit trail: " + secret).doesNotContain(secret);
            assertThat(owner("select payload::text from outbox_event where payload::text like ?",
                    "%" + secret + "%")).as("the events: " + secret).isEmpty();
            assertThat(owner("select attributes::text from audit_record where attributes::text like ?",
                    "%" + secret + "%")).as("the audit records: " + secret).isEmpty();
            assertThat(owner("select outcome::text || key_hash || request_hash from idempotency_record where "
                    + "(outcome::text || key_hash || request_hash) like ?", "%" + secret + "%"))
                    .as("the memory of keyed writes: " + secret).isEmpty();
            for (Meter meter : meters.getMeters()) {
                meter.getId().getTags().forEach(tag -> assertThat(tag.getValue()).as("a metric tag: " + secret)
                        .doesNotContain(secret));
            }
        }
        // What is typed is not echoed in a refusal; the record itself, and the record the caller asked for, is the
        // point.
        for (Response answer : List.of(answers.get(2), answers.get(3), answers.get(5), answers.get(7),
                answers.get(11))) {
            assertThat(answer.body()).doesNotContain(refused).doesNotContain(filter).doesNotContain(token);
        }
        assertThat(answers.get(3).body()).doesNotContain(field);
        assertThat(answers.get(6).body()).doesNotContain(refused).doesNotContain(bulkValue);
    }

    @Test
    void theMetricsOfThePublicApiCountOperationsAndResultsAndNothingElse() {
        Organization organization = organization();
        adminWithEmployee(organization);
        Caller program = administrator(organization);
        program.get(OBJECTS);
        program.get(OBJECTS + "/Nothing__c");
        program.post(EMPLOYEE_RECORDS + "/query", "{}");

        List<Meter> ours = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("platform.publicapi.")).toList();

        assertThat(ours).isNotEmpty();
        for (Meter meter : ours) {
            assertThat(meter.getId().getTags().stream().map(tag -> tag.getKey()).toList())
                    .as(meter.getId().getName()).isSubsetOf("operation", "result", "environment", "application");
        }
        assertThat(meters.get("platform.publicapi.requests").tag("operation", "listObjects")
                .tag("result", "success").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("platform.publicapi.requests").tag("operation", "describeObject")
                .tag("result", "client_error").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("platform.publicapi.request.duration").tag("operation", "queryRecords").timer().count())
                .isGreaterThanOrEqualTo(1);
    }
}

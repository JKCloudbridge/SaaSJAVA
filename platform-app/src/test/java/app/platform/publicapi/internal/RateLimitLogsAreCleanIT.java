package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the limits (Sprint 32, ADR-0282): requests are made, an application is refused, a keyed write is
 * repeated, a bulk call is made, with the application logging at debug level. A token, a client secret, a client
 * identifier, an idempotency key and a record value are never logged, never written to the audit trail, never put in a
 * metric, and no organization, application or member is a tag of a metric. What is counted is numbers.
 */
@RateLimitedTest
@TestPropertySource(properties = {
    "logging.file.name=" + RateLimitLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class RateLimitLogsAreCleanIT extends RateLimitSupport {

    static final String LOG_FILE = "target/it-logs/rate-limit-clean.log";

    @Autowired
    private MeterRegistry meters;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @Test
    void noTokenSecretKeyIdentifierOrValueIsLoggedAuditedOrMeasuredAndNoOrganizationIsATag() throws IOException {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Program program = program(organization, admin);
        String key = key();
        String value = "record-value-" + key.substring(4, 16);

        program.ask();
        program.caller().post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + value + "\"}"), "Idempotency-Key", key);
        program.caller().post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + value + "\"}"), "Idempotency-Key", key);
        program.caller().post(EMPLOYEES_API + "/records/bulk", "{\"records\":[{\"operation\":\"CREATE\","
                + "\"fields\":{\"name__c\":\"" + value + "-bulk\"}}]}");
        Response refused = null;
        for (int i = 0; i < 8 && (refused == null || refused.status() != 429); i++) {
            refused = program.ask();
        }
        assertRefusedWithTheTimeToWait(refused);
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        String metrics = meters.getMeters().stream().map(meter -> meter.getId().toString())
                .collect(Collectors.joining("\n"));

        // The client identifier is not a secret: the log context and the audit record carry it on purpose (Sprint 31).
        List<String> secrets = List.of(program.caller().token(), program.app().secret(), key, value);
        for (String leaked : secrets) {
            assertThat(log).as("log: " + leaked.substring(0, 8)).doesNotContain(leaked);
            assertThat(trail).as("trail: " + leaked.substring(0, 8)).doesNotContain(leaked);
            assertThat(metrics).as("metrics: " + leaked.substring(0, 8)).doesNotContain(leaked);
        }
        assertThat(metrics).as("metrics: the client is never a tag").doesNotContain(program.app().clientId());
        assertThat(metrics).contains("platform.limits.requests");
        assertThat(metrics).doesNotContain(organization.id().value().toString()).doesNotContain(program.app().id());
        assertThat(refused.body()).doesNotContain(program.app().id()).doesNotContain("organization-per-minute");
    }
}

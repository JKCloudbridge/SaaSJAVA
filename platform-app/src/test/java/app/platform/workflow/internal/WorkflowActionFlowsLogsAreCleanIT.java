package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the Sprint 23 flows (ADR-0138 to ADR-0143), added to the one of Sprint 22: a workflow updates and
 * creates records and queues a mail, an action is refused by a rule, fails for a reason nobody named, fails and is
 * tried again, is dead-lettered, replayed and cancelled, a loop is stopped and an event is put back, with the
 * application logging at debug level. A value a person typed (a literal, a record value, the text of a rule and its
 * message, the address of a recipient, the message of a library's failure) is never logged, never written to the
 * audit trail, never kept in an execution, a step or an event, never a tag of a metric, and never in the answer of
 * the history.
 */
@PlatformIntegrationTest
@Import(FailureInjection.class)
@TestPropertySource(properties = {
    "logging.file.name=" + WorkflowActionFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowActionFlowsLogsAreCleanIT extends ActionsSupport {

    static final String LOG_FILE = "target/it-logs/workflow-actions-clean.log";

    @Autowired
    private MeterRegistry meters;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedKeptTaggedOrAnnouncedAcrossTheActionFlows() throws IOException {
        FailureInjection.reset();
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        addField(admin, OBJECT, "owner", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        addField(admin, OBJECT, "f1", "TEXT", "");
        addField(admin, OBJECT, "f2", "TEXT", "");
        String literalText = typed("literal");
        String recordText = typed("record");
        String ruleText = typed("rule");
        String ruleMessage = typed("message");
        String libraryMessage = typed("library");
        String address = organization.admin().person().email();
        String member = organization.admin().person().user().id().toString();
        List<String> typedTexts = List.of(literalText, recordText, ruleText, ruleMessage, libraryMessage, address);

        addRule(admin, "NoRule", "fieldC__c = '" + ruleText + "'", ruleMessage);
        // Update, create, mail; a loop; and one that a rule refuses.
        workflowExists(admin, "Change", recordTrigger("RECORD_CREATED"), "fieldA__c = '" + recordText + "'",
                update("Set", value("fieldB__c", literalText)), mail("Tell", "owner__c"));
        workflowExists(admin, "Refused", recordTrigger("RECORD_CREATED"), "fieldA__c = '" + ruleText + "'",
                update("Set", value("fieldC__c", ruleText)));
        workflowExists(admin, "Ping", fieldChanged("f1__c"), null, update("Set", value("f2__c", literalText)));
        workflowExists(admin, "Pong", fieldChanged("f2__c"), null, update("Set", value("f1__c", literalText)));
        workflowExists(admin, "Flaky", recordTrigger("RECORD_CREATED"), "fieldA__c = '" + libraryMessage + "'",
                update("Set", value("fieldB__c", literalText)));

        // Run to the end with a mail; refused by a rule; transient failure then success; a library failure no one
        // named.
        String first = record(admin, "{\"fieldA__c\":\"" + recordText + "\",\"owner__c\":\"" + member + "\"}");
        record(admin, "{\"fieldA__c\":\"" + ruleText + "\"}");
        settle(organization);
        runWorker();
        FailureInjection.fail("UPDATE_RECORD", 1, () -> new CannotAcquireLockException(libraryMessage));
        record(admin, "{\"fieldA__c\":\"" + libraryMessage + "\"}");
        settle(organization);
        runWorker();
        spendTheWait(organization, rows(organization, "select id::text as id from workflow_execution where status = "
                + "'QUEUED'").get(0).get("id"));
        FailureInjection.fail("UPDATE_RECORD", 1, () -> new IllegalStateException(libraryMessage));
        runWorker();
        // A loop is stopped.
        String looped = record(admin, "{\"fieldA__c\":\"x\"}");
        settle(organization);
        changeRecord(admin, looped, "{\"f1__c\":\"" + recordText + "\"}");
        for (int round = 0; round < 8; round++) {
            settle(organization);
            runWorker();
        }
        // A dead letter is replayed in bulk, one more is cancelled while it waits.
        List<String> dead = rows(organization, "select id::text as id from workflow_execution where status = "
                + "'DEAD_LETTERED'").stream().map(row -> row.get("id")).toList();
        Response replayed = admin.postJson(EXECUTIONS + "/replay", "{\"executions\":[\"" + String.join("\",\"", dead)
                + "\"]}");
        runWorker();
        FailureInjection.fail("UPDATE_RECORD", 1, () -> new CannotAcquireLockException(libraryMessage));
        record(admin, "{\"fieldA__c\":\"" + libraryMessage + "\"}");
        settle(organization);
        runWorker();
        List<String> waiting = rows(organization, "select id::text as id from workflow_execution where status = "
                + "'QUEUED'").stream().map(row -> row.get("id")).toList();
        waiting.forEach(id -> admin.postJson(EXECUTIONS + "/" + id + "/cancel", "{}"));
        settle(organization);
        dispatcher.sampleQueue();
        // Captured before the clean-up purges the rows to check.
        String stored = String.join("\n", rows(organization, "select to_jsonb(e)::text as t from workflow_execution e")
                .stream().map(row -> row.get("t")).toList())
                + String.join("\n", rows(organization, "select to_jsonb(s)::text as t from workflow_step s").stream()
                        .map(row -> row.get("t")).toList())
                + String.join("\n", rows(organization, "select to_jsonb(o)::text as t from outbox_event o "
                        + "where o.event_type like 'record.%' or o.event_type like 'workflow.%'").stream()
                        .map(row -> row.get("t")).toList())
                + String.join("\n", queuedMails(address))
                + String.join("\n", owner("select attributes::text from audit_record where context_tenant_id = "
                        + "cast(? as uuid)", organization.id().value().toString()));
        List<String> shown = new ArrayList<>();
        shown.add(admin.get(EXECUTIONS + "?limit=200").body());
        shown.add(admin.get(EXECUTIONS + "/dead-letters/summary").body());
        shown.add(admin.get(PARKED).body());
        shown.add(admin.get("/api/v1/audit-events?limit=200").body());
        shown.add(admin.get("/api/v1/audit-events?kind=workflow&limit=200").body());
        for (String id : rows(organization, "select id::text as id from workflow_execution").stream()
                .map(row -> row.get("id")).toList()) {
            shown.add(admin.get(EXECUTIONS + "/" + id).body());
        }
        StringBuilder tags = new StringBuilder();
        for (Meter meter : meters.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                tags.append(tag.getValue()).append('\n');
            }
        }
        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(replayed.status()).as(replayed.body()).isEqualTo(200);
        assertThat(log).as("the log has content to check").contains("platform.http");
        assertThat(stored).as("the rows to check exist").contains("workflow_api_name").contains("chain_depth");
        assertThat(rows(organization, "select distinct status from workflow_execution").stream()
                .map(row -> row.get("status"))).as("the flows reached the states to check").contains("COMPLETED",
                "DEAD_LETTERED", "SKIPPED", "CANCELLED");
        assertThat(first).isNotBlank();
        for (String text : typedTexts) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            assertThat(stored).as("typed text is not kept in a row, an event, a mail request or an audit record: "
                    + text).doesNotContain(text);
            assertThat(tags.toString()).as("typed text is not a tag of a metric: " + text).doesNotContain(text);
            for (String body : shown) {
                assertThat(body).as("typed text is not in the history or the audit trail: " + text)
                        .doesNotContain(text);
            }
        }
        assertThat(JsonPath.<List<String>>read(shown.get(4), "$.data[*].type")).as("the trail names what was done")
                .contains("workflow.execution.replayed_many", "workflow.execution.cancelled");
    }
}

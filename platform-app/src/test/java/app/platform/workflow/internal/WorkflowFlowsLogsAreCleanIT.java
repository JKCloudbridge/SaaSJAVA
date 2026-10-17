package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platformapi.CheckWorkflowConditionRequest;
import app.platformapi.CreateWorkflowRequest;
import app.platformapi.QueryFilterNode;
import app.platformapi.UpdateWorkflowRequest;
import app.platformapi.WorkflowActionSpec;
import app.platformapi.WorkflowTriggerSpec;
import app.platformapi.WorkflowView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the Sprint 22 flows (ADR-0137): workflows are made, refused, tried, changed, run (to the end,
 * skipped, not evaluable, failed), scheduled, fired and cleaned up with the application logging at debug level. A
 * condition, a label, a description, a selection and the sample and record values are text of the organization: they
 * are never logged, never written to the audit trail, never kept in an execution or a step, never put in an event,
 * and a refusal never echoes them.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + WorkflowFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowFlowsLogsAreCleanIT extends WorkflowSupport {

    static final String LOG_FILE = "target/it-logs/workflow-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void ok(Response response) {
        assertThat(response.status()).as(response.body()).isBetween(200, 204);
    }

    @Test
    void nothingTypedIsLoggedKeptOrAnnouncedAcrossTheWorkflowFlows() throws IOException {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        String conditionText = typed("cond");
        String label = typed("label");
        String description = typed("description");
        String recordText = typed("record");
        String selectionText = typed("selection");
        String sampleText = typed("sample");
        String refusedText = typed("refused");
        List<String> typedTexts = List.of(conditionText, label, description, recordText, selectionText, sampleText,
                refusedText);

        // Made, with typed text in every place a person can type: label, description, condition.
        ok(admin.postJson(WORKFLOWS + "/" + OBJECT, "{\"name\":\"Watch\",\"label\":\"" + label
                + "\",\"description\":\"" + description + "\",\"active\":true,"
                + "\"trigger\":{\"kind\":\"RECORD_CREATED\"},"
                + "\"condition\":\"fieldA__c = '" + conditionText + "' OR quantity__c / 1 >= 0\",\"actions\":["
                + action("Tell", "watch.event") + "]}"));
        // A scheduled one with typed text in its selection.
        ok(admin.postJson(WORKFLOWS + "/" + OBJECT, "{\"name\":\"Nightly\",\"label\":\"Nightly\",\"description\":\"\","
                + "\"active\":true,\"trigger\":{\"kind\":\"EVERY_DAY\",\"timeZone\":\"UTC\",\"time\":\"09:00\","
                + "\"selection\":{\"op\":\"EQ\",\"field\":\"fieldA__c\",\"values\":[\"" + selectionText + "\"]}},"
                + "\"condition\":null,\"actions\":[" + action("Tell", "nightly.event") + "]}"));
        // One whose condition cannot be evaluated for a record with no quantity to divide by.
        ok(admin.postJson(WORKFLOWS + "/" + OBJECT, createRequest("Divide", recordTrigger("RECORD_CREATED"),
                "10 / quantity__c > 1", action("Tell", "divide.event"))));
        // Refused: the text of the refusal must not repeat what was typed.
        Response refusedGrammar = admin.postJson(WORKFLOWS + "/" + OBJECT, createRequest("Broken",
                recordTrigger("RECORD_CREATED"), "fieldA__c = '" + refusedText + "' AND AND", action("Tell", "a.b")));
        Response refusedField = admin.postJson(WORKFLOWS + "/" + OBJECT, createRequest("Ghost",
                recordTrigger("RECORD_CREATED"), "ghost__c = '" + refusedText + "'", action("Tell", "a.b")));
        Response refusedAction = admin.postJson(WORKFLOWS + "/" + OBJECT, "{\"name\":\"Odd\",\"label\":\"Odd\","
                + "\"trigger\":{\"kind\":\"RECORD_CREATED\"},\"actions\":[{\"type\":\"" + refusedText.toUpperCase()
                + "\",\"name\":\"X\",\"settings\":{}}]}");
        // Tried on sample values.
        Response tried = admin.postJson(WORKFLOWS + "/" + OBJECT + "/check", "{\"condition\":\"fieldA__c = '"
                + sampleText + "'\",\"values\":{\"fieldA__c\":\"" + sampleText + "\"}}");
        Response triedBadValue = admin.postJson(WORKFLOWS + "/" + OBJECT + "/check",
                "{\"condition\":\"quantity__c > 1\","
                + "\"values\":{\"quantity__c\":\"" + sampleText + "\"}}");

        // Run: to the end, skipped (false), not evaluable (division by zero), and failed (a type of a later release).
        record(admin, "{\"fieldA__c\":\"" + conditionText + "\",\"quantity__c\":3}");
        record(admin, "{\"fieldA__c\":\"" + recordText + "\",\"quantity__c\":-1}");
        record(admin, "{\"fieldA__c\":\"" + selectionText + "\",\"quantity__c\":2}");
        record(admin, "{\"fieldA__c\":\"zero\",\"quantity__c\":0}");
        settle(organization);
        runWorker();
        run(organization, "insert into workflow_version (workflow_id, version_number, trigger_kind, trigger, actions, "
                + "created_by, updated_by) select id, 2, 'RECORD_CREATED', cast('{\"kind\":\"RECORD_CREATED\"}' as "
                + "jsonb), cast('[{\"type\":\"RUN_SCRIPT\",\"name\":\"Script\",\"settings\":{}}]' "
                + "as jsonb), cast(? as uuid), cast(? as uuid) "
                + "from workflow where api_name = 'Watch__c'", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        run(organization, "update workflow set definition_version = 2, version = version + 1 "
                + "where api_name = 'Watch__c'");
        record(admin, "{\"fieldA__c\":\"" + recordText + "-again\",\"quantity__c\":1}");
        settle(organization);
        runWorker();
        // Scheduled and fired.
        run(organization, "update workflow_schedule set next_fire_at = now() - interval '1 minute', "
                + "locked_until = null, version = version + 1");
        dispatcher.pollSchedulesOnce();
        runWorker();
        // Captured before the clean-up purges the finished executions: the rows to check must still exist here.
        String stored = String.join("\n", rows(organization, "select to_jsonb(e)::text as t from workflow_execution e")
                .stream().map(row -> row.get("t")).toList())
                + String.join("\n", rows(organization, "select to_jsonb(s)::text as t from workflow_step s").stream()
                        .map(row -> row.get("t")).toList())
                + String.join("\n", rows(organization, "select to_jsonb(o)::text as t from outbox_event o "
                        + "where o.event_type like 'workflow.%'").stream().map(row -> row.get("t")).toList());
        // Cleaned up.
        run(organization, "update workflow_execution set finished_at = now() - interval '100 days', "
                + "version = version + 1 where status in ('COMPLETED', 'SKIPPED', 'FAILED')");
        dispatcher.purgeOnce();
        settle(organization);

        List<String> shown = new ArrayList<>();
        shown.add(admin.get("/api/v1/audit-events?limit=200").body());
        shown.add(admin.get("/api/v1/audit-events?kind=metadata&limit=200").body());
        shown.add(admin.get("/api/v1/audit-events?kind=workflow&limit=200").body());
        shown.add(admin.get("/api/v1/audit-events?kind=retention&limit=200").body());
        // Reading back the admin's own workflow legitimately shows its own label, description and selection filter
        // value: scrub those exactly as typed, the same as any other configuration (not a leak of record data).
        shown.add(admin.get(WORKFLOWS + "?object=" + OBJECT).body().replace(label, "").replace(description, "")
                .replace(selectionText, ""));
        shown.add(admin.get(EXECUTIONS + "?limit=200").body());
        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        assertThat(stored).as("the rows to check exist").contains("workflow_api_name").contains("workflow.signal.");
        for (String text : typedTexts) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            assertThat(stored).as("typed text is not kept in an execution, a step or an event: " + text)
                    .doesNotContain(text);
            for (String body : shown) {
                assertThat(body).as("typed text is not in the audit trail or the history: " + text)
                        .doesNotContain(text);
            }
        }
        assertThat(shown.get(1)).as("the trail does show the names and what changed")
                .contains("metadata.workflow.created").contains("Watch");
        for (Response answer : List.of(refusedGrammar, refusedField, refusedAction, triedBadValue)) {
            assertThat(answer.status()).isGreaterThanOrEqualTo(400);
            assertThat(answer.body()).as("an answer about a mistake does not echo what was typed")
                    .doesNotContain(refusedText).doesNotContain(refusedText.toUpperCase())
                    .doesNotContain(sampleText);
        }
        assertThat(tried.status()).isEqualTo(200);
        assertThat(tried.body()).doesNotContain(sampleText);
    }

    @Test
    void theRecordsOfTheContractNeverPrintWhatWasTyped() {
        String text = typed("secret");
        QueryFilterNode selection = new QueryFilterNode("EQ", null, "fieldA__c", List.of(text));
        WorkflowTriggerSpec trigger = new WorkflowTriggerSpec("EVERY_DAY", List.of(text), text, 3, text, text, text,
                text, selection);
        WorkflowActionSpec action = new WorkflowActionSpec("PUBLISH_EVENT", text, Map.of("event", text));

        assertThat(trigger.toString()).doesNotContain(text);
        assertThat(action.toString()).doesNotContain(text);
        assertThat(new CreateWorkflowRequest(text, text, text, true, trigger, text, List.of(action)).toString())
                .doesNotContain(text);
        assertThat(new UpdateWorkflowRequest(text, text, true, trigger, text, List.of(action), 0L).toString())
                .doesNotContain(text);
        assertThat(new WorkflowView("W__c", "O__c", text, text, true, 1, trigger, text, List.of(action), 0L).toString())
                .doesNotContain(text);
        assertThat(new CheckWorkflowConditionRequest(text, Map.of("f__c", text)).toString()).doesNotContain(text);
    }
}

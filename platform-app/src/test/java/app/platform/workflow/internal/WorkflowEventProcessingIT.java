package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations.Organization;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Turning the event of a record change into executions, on the real outbox (ADR-0133, ADR-0137): a matching event makes
 * exactly one execution, and a redelivered event, a lost marker and two instances racing make no second one; events
 * that match nothing, a switched-off workflow and an organization with no workflow make none; and the tenant of an
 * execution is the tenant of its event.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowEventProcessingIT extends WorkflowSupport {

    private static TestApplication instanceB;

    @Autowired
    private MeterRegistry meters;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start("platform.outbox.enabled=true", "platform.outbox.poll-interval=50ms",
                "platform.workflow.enabled=false");
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    /** Summed across both instances: either one may have claimed and handled a given event. */
    private double events(String result) {
        return counterOf(meters, result) + counterOf(instanceB.bean(MeterRegistry.class), result);
    }

    private static double counterOf(MeterRegistry registry, String result) {
        var counter = registry.find("platform.workflow.events").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- a matching event ----

    @Test
    void aMatchingEventMakesExactlyOneExecutionNamingTheVersionAndTheEvent() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "OnCreate", recordTrigger("RECORD_CREATED"), null, action("Tell", "order.created"));

        String record = record(admin, "{\"fieldA__c\":\"x\"}");
        settle(organization);

        List<Map<String, String>> found = executions(organization);
        assertThat(found).hasSize(1);
        assertThat(found.get(0)).containsEntry("workflow_api_name", "OnCreate__c")
                .containsEntry("workflow_version_number", "1").containsEntry("trigger_kind", "RECORD_CREATED")
                .containsEntry("status", "QUEUED").containsEntry("record_id", record)
                .containsEntry("from_event", "true").containsEntry("from_firing", "false");
    }

    @Test
    void aRedeliveredEventAndALostMarkerStillMakeOnlyOneExecution() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "OnCreate", recordTrigger("RECORD_CREATED"), null, action("Tell", "order.created"));
        record(admin, "{\"fieldA__c\":\"x\"}");
        settle(organization);
        assertThat(executions(organization)).hasSize(1);

        // The outbox delivers the event again: the marker of the consumer says it was handled.
        run(organization, "update outbox_event set status = 'PENDING', delivered_at = null, "
                + "next_attempt_at = now(), version = version + 1 where event_type = 'record.created'");
        settle(organization);
        assertThat(executions(organization)).as("the marker").hasSize(1);

        // The marker is gone (its retention passed, or the consumer was renamed): the unique key is the second line.
        run(organization, "delete from processed_event where consumer = 'workflow.record-events'");
        run(organization, "update outbox_event set status = 'PENDING', delivered_at = null, "
                + "next_attempt_at = now(), version = version + 1 where event_type = 'record.created'");
        settle(organization);
        assertThat(executions(organization)).as("the unique key").hasSize(1);
    }

    @Test
    void aTakenOverLeaseMakesNoSecondExecutionEither() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "OnCreate", recordTrigger("RECORD_CREATED"), null, action("Tell", "order.created"));
        record(admin, "{\"fieldA__c\":\"x\"}");
        settle(organization);

        // A relay that handled the event and died before reporting leaves it claimed with an expired lease.
        run(organization, "update outbox_event set status = 'PENDING', delivered_at = null, "
                + "locked_until = now() - interval '1 minute', next_attempt_at = now(), attempts = 1, "
                + "version = version + 1 where event_type = 'record.created'");
        settle(organization);

        assertThat(executions(organization)).hasSize(1);
    }

    // ---- what matches nothing ----

    @Test
    void anEventThatMatchesNoTriggerMakesNoExecution() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "OnUpdate", recordTrigger("RECORD_UPDATED"), null, action("Tell", "order.updated"));
        workflowExists(admin, "OnA", fieldChanged("fieldA__c"), null, action("Tell", "a.changed"));
        workflowExists(admin, "OnDelete", recordTrigger("RECORD_DELETED"), null, action("Tell", "order.deleted"));

        String record = record(admin, "{\"fieldA__c\":\"x\",\"fieldB__c\":\"y\"}");
        settle(organization);
        assertThat(executions(organization)).as("a creation matches none of these").isEmpty();

        changeRecord(admin, record, "{\"fieldB__c\":\"z\"}");
        settle(organization);
        assertThat(executions(organization)).extracting(row -> row.get("workflow_api_name"))
                .as("a change of another field starts the update workflow only").containsExactly("OnUpdate__c");

        changeRecord(admin, record, "{\"fieldA__c\":\"changed\"}");
        settle(organization);
        assertThat(executions(organization)).extracting(row -> row.get("workflow_api_name"))
                .containsExactlyInAnyOrder("OnUpdate__c", "OnUpdate__c", "OnA__c");

        admin.request("DELETE", RECORDS + "/" + record, null);
        settle(organization);
        assertThat(executions(organization)).extracting(row -> row.get("workflow_api_name"))
                .contains("OnDelete__c").hasSize(4);
    }

    @Test
    void aSwitchedOffWorkflowStartsNothingAndAnOrganizationWithoutWorkflowsCostsOneLookup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        String off = workflowExists(admin, "Off", recordTrigger("RECORD_CREATED"), null, action("Tell", "x.y"));
        switchTo(admin, off, false);
        Organization plain = organization();
        TestBrowser plainAdmin = adminOf(plain);
        double withoutWorkflowBefore = events("no_workflow");

        record(admin, "{\"fieldA__c\":\"x\"}");
        record(plainAdmin, "{\"fieldA__c\":\"x\"}");
        record(plainAdmin, "{\"fieldA__c\":\"y\"}");
        settle(organization);
        settle(plain);

        assertThat(executions(organization)).isEmpty();
        assertThat(executions(plain)).isEmpty();
        assertThat(events("no_workflow") - withoutWorkflowBefore)
                .as("the handler ended at the snapshot lookup for every one of these events (and counted it)")
                .isGreaterThanOrEqualTo(3);
    }

    // ---- tenants ----

    @Test
    void theTenantOfAnExecutionIsTheTenantOfItsEvent() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithData(mine);
        TestBrowser other = adminWithData(theirs);
        workflowExists(admin, "Same", recordTrigger("RECORD_CREATED"), null, action("Tell", "a.b"));
        workflowExists(other, "Same", recordTrigger("RECORD_CREATED"), null, action("Tell", "a.b"));

        record(admin, "{\"fieldA__c\":\"mine\"}");
        settle(mine);
        settle(theirs);

        assertThat(executions(mine)).hasSize(1);
        assertThat(executions(theirs)).as("the same name, object and trigger in another organization").isEmpty();
        assertThat(rows(mine, "select tenant_id::text as t from workflow_execution").get(0).get("t"))
                .isEqualTo(mine.id().value().toString());

        record(other, "{\"fieldA__c\":\"theirs\"}");
        settle(theirs);
        assertThat(executions(theirs)).hasSize(1);
        assertThat(executions(mine)).hasSize(1);
    }

    // ---- two instances ----

    @Test
    void twoInstancesRacingForTheEventsMakeOneExecutionPerRecord() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "OnCreate", recordTrigger("RECORD_CREATED"), null, action("Tell", "order.created"));
        List<String> records = new ArrayList<>();

        for (int i = 0; i < 40; i++) {
            records.add(record(admin, "{\"fieldA__c\":\"r" + i + "\"}"));
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> executions(organization).size() >= 40);
        settle(organization);

        List<Map<String, String>> found = executions(organization);
        assertThat(found).hasSize(40);
        assertThat(found.stream().map(row -> row.get("record_id")).distinct().toList()).hasSize(40)
                .containsExactlyInAnyOrderElementsOf(records);
        assertThat(rows(organization, "select count(*) as n from processed_event where "
                + "consumer = 'workflow.record-events'").get(0).get("n"))
                .as("every event was handled once, by one of the two instances").isEqualTo("40");
    }
}

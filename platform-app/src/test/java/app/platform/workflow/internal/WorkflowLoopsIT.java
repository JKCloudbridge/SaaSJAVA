package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Loop and recursion protection (ADR-0140): a workflow that changes the field it starts on is stopped by the chain, so
 * are two that start each other, a chain of different workflows runs to the depth limit exactly and no further, one
 * workflow starting another on purpose is allowed, and an organization with no workflow for an object pays one lookup.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowLoopsIT extends ActionsSupport {

    @Autowired
    private MeterRegistry meters;

    private record Setup(Organization organization, TestBrowser admin) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        for (int i = 1; i <= 7; i++) {
            addField(admin, OBJECT, "f" + i, "TEXT", "");
        }
        return new Setup(organization, admin);
    }

    /** Lets the outbox and the worker hand over to each other until nothing is left to do. */
    private void drain(Organization organization) {
        for (int round = 0; round < 12; round++) {
            settle(organization);
            runWorker();
        }
        settle(organization);
    }

    private static List<String> outcomes(Organization organization) {
        return executions(organization).stream()
                .map(row -> row.get("workflow_api_name") + ":" + row.get("status") + ":" + row.get("reason")).toList();
    }

    // ---- a workflow that starts itself ----

    @Test
    void aWorkflowThatChangesTheFieldItStartsOnIsStoppedByTheChain() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Self", fieldChanged("fieldA__c"), null,
                update("Set", value("fieldA__c", "looped")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"a\"}");
        settle(setup.organization());
        changeRecord(setup.admin(), id, "{\"fieldA__c\":\"b\"}");

        drain(setup.organization());

        assertThat(outcomes(setup.organization())).containsExactly("Self__c:COMPLETED:null",
                "Self__c:SKIPPED:loop_detected");
        assertThat(fieldOf(setup.admin(), id, "fieldA__c")).isEqualTo("looped");
        assertThat(meters.get("platform.workflow.loops").tag("result", "loop_detected").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void twoWorkflowsThatStartEachOtherStopAtTheSecondTurnOfTheFirst() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Ping", fieldChanged("f1__c"), null, update("Set", value("f2__c", "x")));
        workflowExists(setup.admin(), "Pong", fieldChanged("f2__c"), null, update("Set", value("f1__c", "y")));
        String id = record(setup.admin(), "{\"fieldA__c\":\"a\"}");
        settle(setup.organization());
        changeRecord(setup.admin(), id, "{\"f1__c\":\"start\"}");

        drain(setup.organization());

        assertThat(outcomes(setup.organization())).containsExactly("Ping__c:COMPLETED:null",
                "Pong__c:COMPLETED:null", "Ping__c:SKIPPED:loop_detected");
    }

    @Test
    void oneWorkflowStartingAnotherOnPurposeIsAllowed() {
        Setup setup = setup();
        workflowExists(setup.admin(), "First", fieldChanged("f1__c"), null, update("Set", value("f2__c", "x")));
        workflowExists(setup.admin(), "Second", fieldChanged("f2__c"), null, action("Tell", "second.event"));
        String id = record(setup.admin(), "{\"fieldA__c\":\"a\"}");
        settle(setup.organization());
        changeRecord(setup.admin(), id, "{\"f1__c\":\"start\"}");

        drain(setup.organization());

        assertThat(outcomes(setup.organization())).containsExactly("First__c:COMPLETED:null",
                "Second__c:COMPLETED:null");
        assertThat(signals(setup.organization(), "workflow.signal.second.event")).isEqualTo(1);
    }

    // ---- depth ----

    @Test
    void aChainOfFiveDifferentWorkflowsRunsAndTheSixthIsRefusedForItsDepth() {
        Setup setup = setup();
        for (int i = 1; i <= 6; i++) {
            workflowExists(setup.admin(), "Step" + i, fieldChanged("f" + i + "__c"), null,
                    update("Set", value("f" + (i + 1) + "__c", "v" + i)));
        }
        String id = record(setup.admin(), "{\"fieldA__c\":\"a\"}");
        settle(setup.organization());
        changeRecord(setup.admin(), id, "{\"f1__c\":\"start\"}");

        drain(setup.organization());

        assertThat(outcomes(setup.organization())).containsExactly("Step1__c:COMPLETED:null",
                "Step2__c:COMPLETED:null", "Step3__c:COMPLETED:null", "Step4__c:COMPLETED:null",
                "Step5__c:COMPLETED:null", "Step6__c:SKIPPED:depth_exceeded");
        assertThat(rows(setup.organization(), "select chain_depth from workflow_execution order by created_at, id")
                .stream().map(row -> row.get("chain_depth"))).containsExactly("0", "1", "2", "3", "4", "5");
        assertThat(fieldOf(setup.admin(), id, "f6__c")).isEqualTo("v5");
        assertThat(setup.admin().get(RECORDS + "/" + id).body()).as("the sixth never ran").doesNotContain("v6");
    }

    @Test
    void theChainOfAnExecutionNamesItsRootAndTheWorkflowsBeforeIt() {
        Setup setup = setup();
        workflowExists(setup.admin(), "First", fieldChanged("f1__c"), null, update("Set", value("f2__c", "x")));
        workflowExists(setup.admin(), "Second", fieldChanged("f2__c"), null, action("Tell", "second.event"));
        String id = record(setup.admin(), "{\"fieldA__c\":\"a\"}");
        settle(setup.organization());
        changeRecord(setup.admin(), id, "{\"f1__c\":\"start\"}");

        drain(setup.organization());

        List<java.util.Map<String, String>> chain = rows(setup.organization(), "select chain_root::text as root, "
                + "chain_depth, cardinality(chain_workflows) as before from workflow_execution "
                + "order by created_at, id");
        assertThat(chain.get(0)).containsEntry("chain_depth", "0").containsEntry("before", "0");
        assertThat(chain.get(1)).containsEntry("chain_depth", "1").containsEntry("before", "1");
        assertThat(chain.get(1).get("root")).as("the same root all along").isEqualTo(chain.get(0).get("root"));
    }

    // ---- what costs nothing ----

    @Test
    void anObjectWithNoWorkflowCostsOneLookupAndMakesNoExecution() {
        Setup setup = setup();
        workflowExists(setup.admin(), "Other", recordTrigger("RECORD_CREATED"), null, action("Tell", "x.event"));
        // The workflow is on ObjectA__c; records of ObjectB__c have none.
        double before = counter("no_workflow");
        for (int i = 0; i < 5; i++) {
            Response created = setup.admin().postJson("/api/v1/objects/ObjectB__c/records",
                    "{\"fields\":{\"fieldA__c\":\"b" + i + "\"}}");
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
        settle(setup.organization());

        assertThat(counter("no_workflow") - before).isEqualTo(5.0);
        assertThat(executions(setup.organization())).isEmpty();
    }

    private double counter(String result) {
        return meters.find("platform.workflow.events").tag("result", result).counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }
}

package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Approvals and workflows meet in two places (ADR-0149, ADR-0150): the action {@code START_APPROVAL} submits the record
 * that started a workflow, once, as the platform, with stable reasons when it cannot; and a workflow whose trigger is
 * {@code APPROVAL_OUTCOME} starts when a request ends the way it names. The people are never waited for: the step
 * completes when the request is made, and what follows the decision is another workflow. The dispatcher runs here, so
 * every state is reached by waiting for it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=true",
    "platform.workflow.worker.poll-interval=100ms"})
class ApprovalWorkflowIT extends ApprovalSupport {

    private static final String WORKFLOWS = "/api/v1/metadata/workflows";

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person approver,
            String process) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Auto", null, step("Only", "ANY", user(approver.userId())));
        return new Setup(organization, admin, submitter, approver, process);
    }

    private static String startApproval(String process) {
        return "{\"type\":\"START_APPROVAL\",\"name\":\"Ask\",\"settings\":{\"process\":\"" + process + "\"}}";
    }

    private static String setField(String field, String value) {
        return "{\"type\":\"UPDATE_RECORD\",\"name\":\"Set\",\"settings\":{},\"assignments\":[{\"field\":\"" + field
                + "\",\"value\":\"" + value + "\"}]}";
    }

    private static void workflow(TestBrowser admin, String name, String trigger, String... actions) {
        Response created = admin.postJson(WORKFLOWS + "/" + OBJECT, "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"description\":\"\",\"active\":true,\"trigger\":" + trigger + ",\"condition\":null,"
                + "\"actions\":[" + String.join(",", actions) + "]}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    private static String created() {
        return "{\"kind\":\"RECORD_CREATED\"}";
    }

    private static String outcome(String process, String outcome) {
        return "{\"kind\":\"APPROVAL_OUTCOME\",\"process\":\"" + process + "\",\"outcome\":\"" + outcome + "\"}";
    }

    /** Waits until the organization has this many executions and none of them is still waiting or running. */
    private static void executionsDone(Organization organization, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(100)).until(
                () -> scalar(organization, "select count(*) from workflow_execution") >= count
                        && scalar(organization, "select count(*) from workflow_execution where status in "
                                + "('QUEUED', 'RUNNING')") == 0);
    }

    private static List<Map<String, String>> executions(Organization organization) {
        return rows(organization, "select status, reason, chain_root::text as chain_root from workflow_execution "
                + "order by created_at, id");
    }

    // ---- starting ----

    @Test
    void aWorkflowSubmitsTheRecordOnceAsThePlatformAndTheRecordIsLockedForPeople() {
        Setup setup = setup();
        workflow(setup.admin(), "Ask", created(), startApproval(setup.process()));

        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        executionsDone(setup.organization(), 1);

        assertThat(executions(setup.organization()).get(0)).containsEntry("status", "COMPLETED");
        List<Map<String, String>> requests = rows(setup.organization(), "select status, submitted_by::text as who, "
                + "chain_root::text as chain_root from approval_instance");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).containsEntry("status", "PENDING").containsEntry("who", null);
        assertThat(requests.get(0).get("chain_root")).as("the chain of the run travels with the request").isNotNull();
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"x\"}").status())
                .as("the step is done and the people have the lock").isEqualTo(409);
        assertThat(openRequests(setup.approver().browser())).hasSize(1);
        String request = openRequests(setup.approver().browser()).get(0);
        assertThat(historyOf(setup.admin(), request).get(0)).isEqualTo("SUBMITTED");
        assertThat(optional(instance(setup.admin(), request).body(), "$.data.history[0].actor"))
                .as("no person submitted it").isNull();
    }

    @Test
    void twoWorkflowsThatStartTheSameApprovalMakeOneRequestAndTheLaterOneSaysItWasPending() {
        Setup setup = setup();
        workflow(setup.admin(), "AskOne", created(), startApproval(setup.process()));
        workflow(setup.admin(), "AskTwo", created(), startApproval(setup.process()));

        record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        executionsDone(setup.organization(), 2);

        assertThat(executions(setup.organization())).extracting(row -> row.get("status"))
                .containsExactly("COMPLETED", "COMPLETED");
        assertThat(scalar(setup.organization(), "select count(*) from approval_instance")).isEqualTo(1);
        assertThat(scalar(setup.organization(), "select count(*) from workflow_step where last_reason = "
                + "'already_pending'")).isEqualTo(1);
        settle(setup.organization());
        assertThat(signals(setup.organization(), "approval.submitted")).isEqualTo(1);
    }

    @Test
    void aRecordThatDoesNotMeetTheEntryCriteriaIsNotSubmittedAndTheReasonIsStable() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Large", "quantity__c > 10", step("Only", "ANY",
                user(approver.userId())));
        workflow(admin, "Ask", created(), startApproval(process));

        record(admin, "{\"quantity__c\":5}");
        executionsDone(organization, 1);

        assertThat(executions(organization).get(0)).containsEntry("status", "DEAD_LETTERED")
                .containsEntry("reason", "entry_criteria_not_met");
        assertThat(scalar(organization, "select count(*) from approval_instance")).isZero();
    }

    @Test
    void aFirstStepThatNamesNobodyFailsThePermanentWayAndLocksNothing() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Response group = admin.postJson("/api/v1/groups", "{\"name\":\"empty-" + System.nanoTime()
                + "\",\"description\":\"\"}");
        String empty = JsonPath.read(group.body(), "$.data.id");
        String process = processExists(admin, "Nobody", null, step("Only", "ANY", group(empty)));
        workflow(admin, "Ask", created(), startApproval(process));

        String record = record(admin, "{\"fieldA__c\":\"x\"}");
        executionsDone(organization, 1);

        assertThat(executions(organization).get(0)).containsEntry("status", "DEAD_LETTERED")
                .containsEntry("reason", "no_approver");
        assertThat(scalar(organization, "select count(*) from approval_instance")).isZero();
        assertThat(change(admin, record, "{\"fieldA__c\":\"y\"}").status()).isEqualTo(200);
    }

    // ---- following ----

    @Test
    void aWorkflowStartsWhenARequestEndsTheWayItNamesAndOnlyThen() {
        Setup setup = setup();
        workflow(setup.admin(), "After", outcome(setup.process(), "APPROVED"), setField("fieldB__c", "followed"));
        String approvedRecord = record(setup.submitter().browser(), "{\"fieldA__c\":\"one\"}");
        String rejectedRecord = record(setup.submitter().browser(), "{\"fieldA__c\":\"two\"}");
        String first = submitted(setup.submitter().browser(), approvedRecord, setup.process());
        String second = submitted(setup.submitter().browser(), rejectedRecord, setup.process());

        assertThat(reject(setup.approver().browser(), second, "No").status()).isEqualTo(200);
        settle(setup.organization());
        assertThat(scalar(setup.organization(), "select count(*) from workflow_execution")).as("a rejection is not "
                + "the outcome it named").isZero();

        assertThat(approve(setup.approver().browser(), first).status()).isEqualTo(200);
        executionsDone(setup.organization(), 1);

        assertThat(executions(setup.organization()).get(0)).containsEntry("status", "COMPLETED");
        assertThat(fieldOf(setup.admin(), approvedRecord, "fieldB__c")).isEqualTo("followed");
        assertThat(optional(setup.admin().get(RECORDS + "/" + rejectedRecord).body(), "$.data.fields.fieldB__c"))
                .isNull();
    }

    @Test
    void theChainOfAWorkflowThatStartedAnApprovalSurvivesThePeopleSoALoopThroughThemIsStillSeen() {
        Setup setup = setup();
        workflow(setup.admin(), "Ask", created(), startApproval(setup.process()));
        workflow(setup.admin(), "After", outcome(setup.process(), "APPROVED"), setField("fieldB__c", "followed"));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        executionsDone(setup.organization(), 1);
        String request = openRequests(setup.approver().browser()).get(0);

        assertThat(approve(setup.approver().browser(), request).status()).isEqualTo(200);
        executionsDone(setup.organization(), 2);

        List<Map<String, String>> runs = executions(setup.organization());
        assertThat(runs).extracting(row -> row.get("status")).containsExactly("COMPLETED", "COMPLETED");
        assertThat(runs.get(1).get("chain_root")).as("the follow-up belongs to the chain that began it")
                .isEqualTo(runs.get(0).get("chain_root")).isNotNull();
        assertThat(fieldOf(setup.admin(), record, "fieldB__c")).isEqualTo("followed");
    }
}

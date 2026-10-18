package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The exit criterion of Sprint 24 (ADR-0145 to ADR-0147): a multi-step approval (a manager, then finance) runs to the
 * end with a delegation and a full history. Around it, every way a request can end or move: a rejection at either step,
 * a recall, a reassignment, steps that need one approver or all of them, two approvers who click at once, a stale
 * version, a person who lost the licence, the submitter who may not approve their own request, a step that nobody can
 * decide, and the one pending request a record can have.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalFlowIT extends ApprovalSupport {

    /** An organization with a submitter, a manager, a finance approver and a process of two steps. */
    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person manager,
            Person finance, String process) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person manager = person(organization, admin);
        Person finance = person(organization, admin);
        String process = processExists(admin, createRequest("Expense", null, false, null,
                outcomes(assignment("fieldC__c", "Approved"), assignment("fieldC__c", "Rejected"),
                        assignment("fieldC__c", "Recalled")),
                step("Manager", "ANY", user(manager.userId())), step("Finance", "ANY", user(finance.userId()))));
        return new Setup(organization, admin, submitter, manager, finance, process);
    }

    // ---- the exit criterion ----

    @Test
    void aTwoStepApprovalGoesFromTheManagerToFinanceAndEndsApprovedWithTheFullHistory() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");

        String request = submitted(setup.submitter().browser(), record, setup.process());

        assertThat(statusOf(setup.submitter().browser(), request)).isEqualTo("PENDING");
        assertThat(openRequests(setup.manager().browser())).containsExactly(request);
        assertThat(openRequests(setup.finance().browser())).as("finance is asked only when its step is active")
                .isEmpty();
        assertThat(approve(setup.finance().browser(), request).status()).as("no task yet").isEqualTo(404);

        Response first = approve(setup.manager().browser(), request);
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(first.body(), "$.data.summary.status")).isEqualTo("PENDING");
        assertThat(JsonPath.<Integer>read(first.body(), "$.data.summary.currentStep")).isEqualTo(2);
        assertThat(openRequests(setup.manager().browser())).isEmpty();
        assertThat(openRequests(setup.finance().browser())).containsExactly(request);

        Response last = approve(setup.finance().browser(), request);
        assertThat(last.status()).as(last.body()).isEqualTo(200);

        assertThat(statusOf(setup.admin(), request)).isEqualTo("APPROVED");
        assertThat(fieldOf(setup.admin(), record, "fieldC__c")).as("the outcome update").isEqualTo("Approved");
        assertThat(historyOf(setup.admin(), request)).containsSubsequence("SUBMITTED", "STEP_ACTIVATED",
                "TASK_APPROVED", "STEP_APPROVED", "STEP_ACTIVATED", "TASK_APPROVED", "STEP_APPROVED", "APPROVED")
                .contains("OUTCOME_APPLIED");
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"after\"}").status())
                .as("approved means unlocked").isEqualTo(200);
    }

    @Test
    void aDelegateIsAskedBesideTheManagerAndTheHistoryNamesBoth() {
        Setup setup = setup();
        Person delegate = person(setup.organization(), setup.admin());
        Response made = setup.manager().browser().postJson(DELEGATIONS, "{\"delegateId\":\"" + delegate.userId()
                + "\",\"startsOn\":\"" + today() + "\",\"endsOn\":\"" + inDays(2) + "\",\"timeZone\":\"UTC\"}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");

        String request = submitted(setup.submitter().browser(), record, setup.process());

        assertThat(delegatedRequests(delegate.browser())).containsExactly(request);
        assertThat(openRequests(delegate.browser())).as("not among their own").isEmpty();
        assertThat(openRequests(setup.manager().browser())).as("the delegator keeps the task")
                .containsExactly(request);

        assertThat(approve(delegate.browser(), request).status()).isEqualTo(200);

        Response view = instance(setup.admin(), request);
        List<String> onBehalf = JsonPath.read(view.body(),
                "$.data.history[?(@.kind=='TASK_APPROVED')].onBehalfOf.userId");
        assertThat(onBehalf).containsExactly(setup.manager().userId());
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.history[?(@.kind=='TASK_APPROVED')].actor.userId"))
                .containsExactly(delegate.userId());
        assertThat(openRequests(setup.manager().browser())).as("the delegator's task closed with the step")
                .isEmpty();
        assertThat(approve(setup.manager().browser(), request).status()).as("the step is done")
                .isIn(404, 409);
    }

    // ---- ending otherwise ----

    @Test
    void aRejectionAtTheFirstStepEndsTheRequestAndNeverAsksFinance() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        assertThat(decide(setup.manager().browser(), request, "reject", null).status())
                .as("a rejection needs a reason").isEqualTo(400);
        Response rejected = reject(setup.manager().browser(), request, "Too expensive");

        assertThat(rejected.status()).as(rejected.body()).isEqualTo(200);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("REJECTED");
        assertThat(openRequests(setup.finance().browser())).isEmpty();
        assertThat(fieldOf(setup.admin(), record, "fieldC__c")).isEqualTo("Rejected");
        assertThat(historyOf(setup.admin(), request)).contains("REJECTED").doesNotContain("APPROVED");
        assertThat(JsonPath.<List<String>>read(instance(setup.manager().browser(), request).body(),
                "$.data.history[?(@.kind=='REJECTED')].comment")).containsExactly("Too expensive");
    }

    @Test
    void aRejectionAtTheSecondStepEndsAnApprovedFirstStepAsRejected() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());
        assertThat(approve(setup.manager().browser(), request).status()).isEqualTo(200);

        assertThat(reject(setup.finance().browser(), request, "No budget").status()).isEqualTo(200);

        assertThat(statusOf(setup.admin(), request)).isEqualTo("REJECTED");
        assertThat(JsonPath.<List<String>>read(instance(setup.admin(), request).body(), "$.data.steps[*].status"))
                .containsExactly("APPROVED", "REJECTED");
    }

    @Test
    void theSubmitterRecallsAndAnUnrelatedMemberCannotEvenSeeTheRequest() {
        Setup setup = setup();
        Person stranger = person(setup.organization(), setup.admin());
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        assertThat(decide(stranger.browser(), request, "recall", null).status()).as("not theirs").isEqualTo(404);
        assertThat(instance(stranger.browser(), request).status()).isEqualTo(404);
        Response recalled = decide(setup.submitter().browser(), request, "recall", "Changed my mind");

        assertThat(recalled.status()).as(recalled.body()).isEqualTo(200);
        assertThat(statusOf(setup.submitter().browser(), request)).isEqualTo("RECALLED");
        assertThat(fieldOf(setup.admin(), record, "fieldC__c")).isEqualTo("Recalled");
        assertThat(openRequests(setup.manager().browser())).isEmpty();
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"edited\"}").status())
                .as("recalled means unlocked").isEqualTo(200);
        assertThat(approve(setup.manager().browser(), request).status()).as("a decision after the end")
                .isIn(404, 409);
    }

    @Test
    void onlyAnAdministratorWhoMayOverrideTheLockCancelsAndCancelledIsItsOwnState() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        assertThat(decide(setup.submitter().browser(), request, "cancel", null).status()).isEqualTo(403);
        assertThat(decide(setup.admin(), request, "cancel", "Policy changed").status()).isEqualTo(200);

        assertThat(statusOf(setup.admin(), request)).isEqualTo("CANCELLED");
        assertThat(historyOf(setup.admin(), request)).contains("CANCELLED");
    }

    // ---- moving ----

    @Test
    void anApproverHandsTheirTaskToOnePersonOnceAndTheNewHolderDecides() {
        Setup setup = setup();
        Person deputy = person(setup.organization(), setup.admin());
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        assertThat(reassign(setup.manager().browser(), request, setup.manager().userId()).status())
                .as("to oneself, who holds a task already").isEqualTo(400);
        Response handed = reassign(setup.manager().browser(), request, deputy.userId());
        assertThat(handed.status()).as(handed.body()).isEqualTo(200);

        assertThat(openRequests(deputy.browser())).containsExactly(request);
        assertThat(openRequests(setup.manager().browser())).isEmpty();
        assertThat(approve(setup.manager().browser(), request).status()).as("the task is no longer theirs")
                .isEqualTo(409);
        assertThat(approve(deputy.browser(), request).status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(instance(setup.admin(), request).body(),
                "$.data.history[?(@.kind=='REASSIGNED')].target.userId")).containsExactly(deputy.userId());
    }

    @Test
    void theSubmitterCannotBeAskedToDecideTheirOwnRequestAndAProcessMayAllowIt() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        String strict = processExists(admin, "Strict", null, step("Self", "ANY", user(submitter.userId())));
        String lenient = processExists(admin, createRequest("Lenient", null, true, null, null,
                step("Self", "ANY", user(submitter.userId()))));
        String record = record(submitter.browser(), "{\"fieldA__c\":\"trip\"}");

        Response refused = submit(submitter.browser(), record, strict, null);
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(scalar(organization, "select count(*) from approval_instance")).as("no request, no lock")
                .isZero();
        assertThat(change(submitter.browser(), record, "{\"fieldA__c\":\"free\"}").status()).isEqualTo(200);

        String request = submitted(submitter.browser(), record, lenient);
        assertThat(approve(submitter.browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(submitter.browser(), request)).isEqualTo("APPROVED");
    }

    // ---- how many must agree ----

    @Test
    void aStepThatNeedsEveryApproverWaitsForTheLastOne() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person first = person(organization, admin);
        Person second = person(organization, admin);
        String process = processExists(admin, "Both", null,
                step("Both", "ALL", user(first.userId()), user(second.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"), process);

        assertThat(approve(first.browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(admin, request)).as("one of two").isEqualTo("PENDING");
        assertThat(approve(first.browser(), request).status()).as("the same person twice").isIn(404, 409);
        assertThat(approve(second.browser(), request).status()).isEqualTo(200);

        assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
    }

    @Test
    void aStepThatNeedsOneApproverEndsWithTheFirstAndTheSecondIsToldItIsDone() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person first = person(organization, admin);
        Person second = person(organization, admin);
        String process = processExists(admin, "Either", null,
                step("Either", "ANY", user(first.userId()), user(second.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"), process);
        long seen = instanceVersion(second.browser(), request);

        assertThat(approve(first.browser(), request).status()).isEqualTo(200);
        Response late = decide(second.browser(), request, "approve", null, seen);

        assertThat(late.status()).as(late.body()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
        assertThat(rows(organization, "select status from approval_assignment order by created_at, id").stream()
                .map(row -> row.get("status")).toList()).containsExactlyInAnyOrder("APPROVED", "CLOSED");
    }

    @Test
    void twoApproversWhoClickAtTheSameMomentMakeOneOutcomeAndOneRefusal() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person first = person(organization, admin);
        Person second = person(organization, admin);
        String process = processExists(admin, "Race", null,
                step("Either", "ANY", user(first.userId()), user(second.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"), process);
        long version = instanceVersion(first.browser(), request);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> one = () -> {
                go.await();
                return decide(first.browser(), request, "approve", null, version).status();
            };
            Callable<Integer> two = () -> {
                go.await();
                return decide(second.browser(), request, "approve", null, version).status();
            };
            Future<Integer> a = pool.submit(one);
            Future<Integer> b = pool.submit(two);
            go.countDown();

            assertThat(List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        settle(organization);
        assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
        assertThat(signals(organization, "approval.approved")).as("one ending, one event").isEqualTo(1);
        assertThat(rows(organization, "select kind from approval_history where kind = 'APPROVED'")).hasSize(1);
    }

    @Test
    void aDecisionOnAStaleVersionIsRefusedAndNamesNothing() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        Response stale = decide(setup.manager().browser(), request, "approve", null, 999);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(stale.body()).doesNotContain(request).doesNotContain(record);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("PENDING");
    }

    @Test
    void anApproverWhoLostTheLicenceCannotDecideUntilTheyHaveOneAgain() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        Response released = setup.admin().request("DELETE", MEMBERS + "/" + setup.manager().member().membership()
                + "/licence", null);
        assertThat(released.status()).as(released.body()).isIn(200, 204);
        assertThat(approve(setup.manager().browser(), request).status()).isEqualTo(403);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("PENDING");

        Response given = setup.admin().request("PUT", MEMBERS + "/" + setup.manager().member().membership()
                + "/licence", null);
        assertThat(given.status()).as(given.body()).isIn(200, 204);
        assertThat(approve(setup.manager().browser(), request).status()).isEqualTo(200);
    }

    // ---- who may be asked, and when nobody can ----

    @Test
    void entryCriteriaDecideWhichRecordsMaySubmit() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Large", "quantity__c > 10", step("One", "ANY", user(approver.userId())));
        String small = record(submitter.browser(), "{\"quantity__c\":5}");
        String large = record(submitter.browser(), "{\"quantity__c\":50}");

        assertThat(submit(submitter.browser(), small, process, null).status()).isEqualTo(409);
        assertThat(submit(submitter.browser(), large, null, null).status()).as("the only process that fits")
                .isEqualTo(201);
    }

    @Test
    void aRecordHasOnePendingRequestAndTheSubmitterGetsTheSameOneBack() {
        Setup setup = setup();
        Person other = person(setup.organization(), setup.admin());
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        assertThat(submitted(setup.submitter().browser(), record, setup.process())).isEqualTo(request);
        Response second = submit(other.browser(), record, setup.process(), null);

        assertThat(second.status()).as(second.body()).isEqualTo(409);
        assertThat(scalar(setup.organization(), "select count(*) from approval_instance where status = 'PENDING'"))
                .isEqualTo(1);
    }

    @Test
    void aStepNobodyCanDecideWaitsForAnAdministratorWhoAsksSomeoneElse() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person first = person(organization, admin);
        Person rescuer = person(organization, admin);
        Response group = admin.postJson("/api/v1/groups", "{\"name\":\"empty-" + System.nanoTime()
                + "\",\"description\":\"\"}");
        assertThat(group.status()).as(group.body()).isEqualTo(201);
        String empty = JsonPath.read(group.body(), "$.data.id");
        String process = processExists(admin, "Stuck", null, step("First", "ANY", user(first.userId())),
                step("Second", "ANY", group(empty)));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"), process);

        assertThat(approve(first.browser(), request).status()).isEqualTo(200);

        Response waiting = instance(admin, request);
        assertThat(JsonPath.<String>read(waiting.body(), "$.data.summary.status")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(waiting.body(), "$.data.summary.attention")).isEqualTo("no_approver");
        assertThat(JsonPath.<List<String>>read(admin.get(INSTANCES + "?attention=true").body(), "$.data[*].id"))
                .contains(request);

        Response asked = assign(admin, request, rescuer.userId());
        assertThat(asked.status()).as(asked.body()).isEqualTo(200);
        assertThat(optional(asked.body(), "$.data.summary.attention")).isNull();
        assertThat(historyOf(admin, request)).contains("ATTENTION_RAISED", "ASSIGNED", "ATTENTION_CLEARED");
        assertThat(approve(rescuer.browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
    }

    @Test
    void aFirstStepThatNamesNobodyRefusesTheSubmitAndLocksNothing() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Response group = admin.postJson("/api/v1/groups", "{\"name\":\"nobody-" + System.nanoTime()
                + "\",\"description\":\"\"}");
        String empty = JsonPath.read(group.body(), "$.data.id");
        String process = processExists(admin, "Nobody", null, step("First", "ANY", group(empty)));
        String record = record(submitter.browser(), "{\"fieldA__c\":\"x\"}");

        Response refused = submit(submitter.browser(), record, process, null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(scalar(organization, "select count(*) from approval_instance")).isZero();
        assertThat(change(submitter.browser(), record, "{\"fieldA__c\":\"y\"}").status()).isEqualTo(200);
    }

    @Test
    void aRequestKeepsTheDefinitionItStartedUnderWhenTheProcessChangesLater() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        Response changed = setup.admin().request("PUT", DEFINITIONS + "/" + OBJECT + "/" + setup.process(),
                updateRequest("Expense", true, null, false, null, null,
                        processVersion(setup.admin(), setup.process()),
                        step("Only", "ANY", user(setup.finance().userId()))));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);

        assertThat(JsonPath.<Integer>read(instance(setup.admin(), request).body(), "$.data.summary.processVersion"))
                .isEqualTo(1);
        assertThat(approve(setup.manager().browser(), request).status()).as("still the manager first").isEqualTo(200);
        assertThat(JsonPath.<Integer>read(instance(setup.admin(), request).body(), "$.data.summary.currentStep"))
                .isEqualTo(2);
    }
}

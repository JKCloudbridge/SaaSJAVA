package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A step nobody can decide is skipped when its process says so (FA4A, ADR-0187): in the middle, at the end, at the
 * start, twice in a row, with a fallback that answers, and never when the process did not say so. A skipped step is in
 * the history, has no task and no timer, and a request whose last steps are skipped is approved the way a last approval
 * approves it, outcome updates included.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalSkippedStepIT extends ApprovalSupport {

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person first, Person second,
            String empty) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Response group = admin.postJson("/api/v1/groups", "{\"name\":\"empty-" + System.nanoTime()
                + "\",\"description\":\"\"}");
        assertThat(group.status()).as(group.body()).isEqualTo(201);
        return new Setup(organization, admin, person(organization, admin), person(organization, admin),
                person(organization, admin), JsonPath.read(group.body(), "$.data.id"));
    }

    private static String withOutcomes(String name, String fallback, String... steps) {
        return createRequest(name, null, false, fallback,
                outcomes(assignment("fieldB__c", "Approved"), assignment("fieldB__c", "Rejected"),
                        assignment("fieldB__c", "Recalled")), steps);
    }

    private static List<Map<String, String>> stepsOf(Setup setup, String request) {
        return rows(setup.organization(), "select s.position, s.status, "
                + "(select count(*) from approval_assignment a where a.step_id = s.id) as tasks "
                + "from approval_step_instance s where s.instance_id = ? order by s.position", java.util.UUID
                .fromString(request));
    }

    @Test
    void aMiddleStepNobodyCanDecideIsSkippedAndTheNextOneIsAsked() {
        Setup setup = setup();
        String process = processExists(setup.admin(), withOutcomes("Middle", null,
                step("First", "ANY", user(setup.first().userId())),
                skippableStep("Optional", "ANY", group(setup.empty())),
                step("Last", "ANY", user(setup.second().userId()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, process);

        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);

        List<String> history = historyOf(setup.admin(), request);
        assertThat(history).containsSubsequence("SUBMITTED", "STEP_ACTIVATED", "TASK_APPROVED", "STEP_APPROVED",
                "STEP_SKIPPED", "STEP_ACTIVATED");
        assertThat(JsonPath.<Number>read(instance(setup.admin(), request).body(), "$.data.summary.currentStep")
                .intValue()).isEqualTo(3);
        assertThat(stepsOf(setup, request)).extracting(row -> row.get("status")).containsExactly("APPROVED",
                "SKIPPED", "ACTIVE");
        assertThat(stepsOf(setup, request).get(1)).containsEntry("tasks", "0");
        assertThat(openRequests(setup.second().browser())).contains(request);
        assertThat(audited(setup.organization(), "approval.step.skipped")).isEqualTo(1);

        assertThat(approve(setup.second().browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("APPROVED");
    }

    @Test
    void theLastStepsNobodyCanDecideAreSkippedAndTheRequestIsApprovedWithItsOutcomeUpdates() {
        Setup setup = setup();
        String process = processExists(setup.admin(), withOutcomes("Last", null,
                step("First", "ANY", user(setup.first().userId())),
                skippableStep("OptionalOne", "ANY", group(setup.empty())),
                skippableStep("OptionalTwo", "ANY", group(setup.empty()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, process);

        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);

        assertThat(statusOf(setup.admin(), request)).isEqualTo("APPROVED");
        assertThat(historyOf(setup.admin(), request)).containsSubsequence("STEP_APPROVED", "STEP_SKIPPED",
                "STEP_SKIPPED", "APPROVED", "OUTCOME_APPLIED");
        assertThat(fieldOf(setup.admin(), record, "fieldB__c")).isEqualTo("Approved");
        assertThat(stepsOf(setup, request)).extracting(row -> row.get("status")).containsExactly("APPROVED",
                "SKIPPED", "SKIPPED");
        // The record is open again and nothing waits for anybody.
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"y\"}").status()).isEqualTo(200);
        assertThat(scalar(setup.organization(), "select count(*) from approval_assignment where status = 'OPEN'"))
                .isZero();
    }

    @Test
    void aFirstStepNobodyCanDecideIsSkippedAndTheSecondIsAskedAtOnce() {
        Setup setup = setup();
        String process = processExists(setup.admin(), withOutcomes("First", null,
                skippableStep("Optional", "ANY", group(setup.empty())),
                step("Real", "ANY", user(setup.first().userId()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");

        String request = submitted(setup.submitter().browser(), record, process);

        assertThat(historyOf(setup.admin(), request)).containsExactly("SUBMITTED", "STEP_SKIPPED", "STEP_ACTIVATED");
        assertThat(JsonPath.<Number>read(instance(setup.admin(), request).body(), "$.data.summary.currentStep")
                .intValue()).isEqualTo(2);
        assertThat(openRequests(setup.first().browser())).contains(request);
        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("APPROVED");
    }

    @Test
    void twoStepsInARowThatNobodyCanDecideAreBothSkipped() {
        Setup setup = setup();
        String process = processExists(setup.admin(), withOutcomes("Twice", null,
                step("First", "ANY", user(setup.first().userId())),
                skippableStep("OptionalOne", "ANY", group(setup.empty())),
                skippableStep("OptionalTwo", "ANY", group(setup.empty())),
                step("Last", "ANY", user(setup.second().userId()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, process);

        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);

        assertThat(stepsOf(setup, request)).extracting(row -> row.get("status")).containsExactly("APPROVED",
                "SKIPPED", "SKIPPED", "ACTIVE");
        assertThat(openRequests(setup.second().browser())).contains(request);
    }

    @Test
    void aRequestWhereEveryStepIsSkippableAndNobodyIsNamedIsRefusedAndLocksNothing() {
        Setup setup = setup();
        String process = processExists(setup.admin(), withOutcomes("Nobody", null,
                skippableStep("OptionalOne", "ANY", group(setup.empty())),
                skippableStep("OptionalTwo", "ANY", group(setup.empty()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");

        Response refused = submit(setup.submitter().browser(), record, process, null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("first step");
        assertThat(scalar(setup.organization(), "select count(*) from approval_instance")).isZero();
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"y\"}").status()).isEqualTo(200);
    }

    @Test
    void theFallbackIsAskedBeforeAStepIsSkipped() {
        Setup setup = setup();
        String process = processExists(setup.admin(), withOutcomes("Fallback", user(setup.second().userId()),
                step("First", "ANY", user(setup.first().userId())),
                skippableStep("Optional", "ANY", group(setup.empty()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, process);

        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);

        assertThat(historyOf(setup.admin(), request)).doesNotContain("STEP_SKIPPED");
        assertThat(statusOf(setup.admin(), request)).isEqualTo("PENDING");
        assertThat(openRequests(setup.second().browser())).contains(request);
    }

    @Test
    void aStepNotMarkedToBeSkippedBehavesAsItAlwaysDid() {
        Setup setup = setup();
        String firstEmpty = processExists(setup.admin(), withOutcomes("FirstEmpty", null,
                step("Only", "ANY", group(setup.empty()))));
        String laterEmpty = processExists(setup.admin(), withOutcomes("LaterEmpty", null,
                step("First", "ANY", user(setup.first().userId())), step("Stuck", "ANY", group(setup.empty()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");

        assertThat(submit(setup.submitter().browser(), record, firstEmpty, null).status()).isEqualTo(409);

        String request = submitted(setup.submitter().browser(), record, laterEmpty);
        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(instance(setup.admin(), request).body(), "$.data.summary.attention"))
                .isEqualTo("no_approver");
        assertThat(historyOf(setup.admin(), request)).doesNotContain("STEP_SKIPPED");
    }

    @Test
    void theFlagRoundTripsThroughTheDefinitionAndTheTryItPreviewSaysWhichStepWouldBeSkipped() {
        Setup setup = setup();
        String process = processExists(setup.admin(), withOutcomes("Preview", null,
                step("First", "ANY", user(setup.first().userId())),
                skippableStep("Optional", "ANY", group(setup.empty()))));
        Response read = setup.admin().get(DEFINITIONS + "/" + OBJECT + "/" + process);
        assertThat(JsonPath.<List<Boolean>>read(read.body(), "$.data.steps[*].skipWhenNobody"))
                .containsExactly(false, true);
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");

        Response preview = setup.admin().postJson(DEFINITIONS + "/" + OBJECT + "/resolve", "{\"recordId\":\""
                + record + "\",\"steps\":[" + step("First", "ANY", user(setup.first().userId())) + ","
                + skippableStep("Optional", "ANY", group(setup.empty())) + "]}");

        assertThat(preview.status()).as(preview.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Boolean>>read(preview.body(), "$.data.steps[*].skipped"))
                .containsExactly(false, true);
        assertThat(JsonPath.<List<Boolean>>read(preview.body(), "$.data.steps[*].nobody"))
                .containsExactly(false, true);
    }
}

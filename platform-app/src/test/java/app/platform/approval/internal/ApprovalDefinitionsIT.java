package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Approval processes as metadata (ADR-0145, ADR-0146, ADR-0149): made live as a release of one change, changed into a
 * new
 * immutable version only when the definition changed, switched on and off without one, removed only when no request is
 * waiting, guarded by the dependency graph (a field a process names cannot go), tried before use, and carried through
 * change sets and rolled back like every other definition. Every refusal names the place and repeats nothing typed.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalDefinitionsIT extends ApprovalSupport {

    private static final String SETS = "/api/v1/metadata/change-sets";
    private static final String RELEASES = "/api/v1/metadata/releases";

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person approver) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        return new Setup(organization, admin, person(organization, admin), person(organization, admin));
    }

    private static List<String> names(TestBrowser admin) {
        return JsonPath.read(admin.get(DEFINITIONS + "?object=" + OBJECT).body(), "$.data[*].apiName");
    }

    // ---- making and changing ----

    @Test
    void aProcessIsMadeLiveWithTheNameTurnedIntoTheApiNameAndReadBackAsWritten() {
        Setup setup = setup();
        String request = createRequest("Expense", "quantity__c > 10", false, null,
                outcomes(assignment("fieldC__c", "Approved"), "", ""),
                step("Manager", "ANY", user(setup.approver().userId())), step("Finance", "ALL",
                        bare("OWNER")));

        Response created = setup.admin().postJson(DEFINITIONS + "/" + OBJECT, request);

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.apiName")).isEqualTo("Expense__c");
        Response read = setup.admin().get(DEFINITIONS + "/" + OBJECT + "/Expense__c");
        assertThat(JsonPath.<String>read(read.body(), "$.data.entryCriteria")).isEqualTo("quantity__c > 10");
        assertThat(JsonPath.<List<String>>read(read.body(), "$.data.steps[*].name")).containsExactly("Manager",
                "Finance");
        assertThat(JsonPath.<List<String>>read(read.body(), "$.data.steps[*].completion")).containsExactly("ANY",
                "ALL");
        assertThat(JsonPath.<Integer>read(read.body(), "$.data.definitionVersion")).isEqualTo(1);
        assertThat(names(setup.admin())).containsExactly("Expense__c");
    }

    @Test
    void aNewVersionIsMadeOnlyWhenTheDefinitionChangesNotForALabelOrASwitch() {
        Setup setup = setup();
        String process = processExists(setup.admin(), "Expense", null, step("One", "ANY",
                user(setup.approver().userId())));
        String path = DEFINITIONS + "/" + OBJECT + "/" + process;

        Response relabelled = setup.admin().request("PUT", path, updateRequest("Renamed", true, null, false, null,
                null, processVersion(setup.admin(), process), step("One", "ANY", user(setup.approver().userId()))));
        Response off = setup.admin().postJson(path + "/activation", "{\"active\":false,\"version\":"
                + processVersion(setup.admin(), process) + "}");
        Response changed = setup.admin().request("PUT", path, updateRequest("Renamed", false, null, false, null,
                null, processVersion(setup.admin(), process), step("One", "ALL", user(setup.approver().userId()))));

        assertThat(relabelled.status()).as(relabelled.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(relabelled.body(), "$.data.definitionVersion")).isEqualTo(1);
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(off.body(), "$.data.definitionVersion")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(changed.body(), "$.data.definitionVersion")).isEqualTo(2);
        assertThat(scalar(setup.organization(), "select count(*) from approval_process_version")).isEqualTo(2);
    }

    @Test
    void aChangeThatNamesTheWrongVersionIsRefusedAndChangesNothing() {
        Setup setup = setup();
        String process = processExists(setup.admin(), "Expense", null, step("One", "ANY",
                user(setup.approver().userId())));

        Response stale = setup.admin().request("PUT", DEFINITIONS + "/" + OBJECT + "/" + process,
                updateRequest("Other", true, null, false, null, null, 99,
                        step("One", "ANY", user(setup.approver().userId()))));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(JsonPath.<String>read(setup.admin().get(DEFINITIONS + "/" + OBJECT + "/" + process).body(),
                "$.data.label")).isEqualTo("Expense");
    }

    // ---- refused at the door ----

    @Test
    void aDefinitionThatBreaksTheRulesIsRefusedAtThePlaceAndRepeatsNothingTyped() {
        Setup setup = setup();
        String good = step("One", "ANY", user(setup.approver().userId()));

        Response criteria = createProcess(setup.admin(), "Bad", "quantity__c >", good);
        Response completion = createProcess(setup.admin(), "Worse", null, step("One", "SECRET-WORD",
                user(setup.approver().userId())));
        Response kind = createProcess(setup.admin(), "Worst", null, step("One", "ANY",
                "{\"kind\":\"SECRET-KIND\",\"value\":null}"));
        Response bareValue = createProcess(setup.admin(), "Odd", null, step("One", "ANY",
                "{\"kind\":\"OWNER\",\"value\":\"x\"}"));

        for (Response refused : List.of(criteria, completion, kind, bareValue)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(code(refused)).isEqualTo("VALIDATION_ERROR");
            assertThat(refused.body()).doesNotContain("SECRET-");
        }
        assertThat(refused(criteria)).contains("entryCriteria");
        assertThat(refused(completion)).contains("steps[0].completion");
        assertThat(refused(kind)).contains("steps[0].approvers[0].kind");
        assertThat(refused(bareValue)).contains("steps[0].approvers[0].value");
        assertThat(names(setup.admin())).isEmpty();
    }

    private static List<String> refused(Response response) {
        return JsonPath.<java.util.Map<String, Object>>read(response.body(), "$.error.fields").keySet().stream()
                .toList();
    }

    @Test
    void aProcessThatNamesAFieldThatDoesNotExistIsRefusedByTheDependencyCheck() {
        Setup setup = setup();

        Response refused = setup.admin().postJson(DEFINITIONS + "/" + OBJECT, createRequest("Ghost", null, false,
                null, outcomes(assignment("ghost__c", "x"), "", ""),
                step("One", "ANY", user(setup.approver().userId()))));

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("approval process Ghost__c").contains("ghost__c");
        assertThat(names(setup.admin())).isEmpty();
    }

    @Test
    void aFieldAProcessNamesCannotBeRemovedWhileTheProcessNamesIt() {
        Setup setup = setup();
        processExists(setup.admin(), createRequest("Expense", "quantity__c > 1", false, null,
                outcomes(assignment("fieldC__c", "Approved"), "", ""),
                step("One", "ANY", user(setup.approver().userId()))));

        Response criteriaField = setup.admin().request("DELETE", METADATA_OBJECTS + "/" + OBJECT
                + "/fields/quantity__c", null);
        Response outcomeField = setup.admin().request("DELETE", METADATA_OBJECTS + "/" + OBJECT
                + "/fields/fieldC__c", null);

        assertThat(criteriaField.status()).as(criteriaField.body()).isEqualTo(409);
        assertThat(outcomeField.status()).as(outcomeField.body()).isEqualTo(409);
        assertThat(criteriaField.body()).contains("Expense__c");
    }

    // ---- trying ----

    @Test
    void theCheckSaysWhetherARecordWithTheseValuesCouldBeSubmittedAndStoresNothing() {
        Setup setup = setup();
        String path = DEFINITIONS + "/" + OBJECT + "/check";

        Response runs = setup.admin().postJson(path, "{\"entryCriteria\":\"quantity__c > 10\","
                + "\"values\":{\"quantity__c\":\"50\"}}");
        Response skipped = setup.admin().postJson(path, "{\"entryCriteria\":\"quantity__c > 10\","
                + "\"values\":{\"quantity__c\":\"5\"}}");
        Response broken = setup.admin().postJson(path, "{\"entryCriteria\":\"quantity__c >\",\"values\":{}}");

        assertThat(JsonPath.<String>read(runs.body(), "$.data.outcome")).isEqualTo("RUNS");
        assertThat(JsonPath.<String>read(skipped.body(), "$.data.outcome")).isEqualTo("SKIPPED");
        assertThat(broken.status()).isEqualTo(400);
        assertThat(scalar(setup.organization(), "select count(*) from approval_process")).isZero();
    }

    // ---- switching and removing ----

    @Test
    void aProcessThatIsSwitchedOffStartsNothingNewButARequestAlreadyWaitingFinishesWithItsLock() {
        Setup setup = setup();
        String process = processExists(setup.admin(), "Expense", null, step("One", "ANY",
                user(setup.approver().userId())));
        String waiting = record(setup.submitter().browser(), "{\"fieldA__c\":\"one\"}");
        String fresh = record(setup.submitter().browser(), "{\"fieldA__c\":\"two\"}");
        String request = submitted(setup.submitter().browser(), waiting, process);

        Response off = setup.admin().postJson(DEFINITIONS + "/" + OBJECT + "/" + process + "/activation",
                "{\"active\":false,\"version\":" + processVersion(setup.admin(), process) + "}");

        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(submit(setup.submitter().browser(), fresh, process, null).status()).isEqualTo(409);
        assertThat(change(setup.submitter().browser(), waiting, "{\"fieldA__c\":\"x\"}").status())
                .as("the lock stays").isEqualTo(409);
        assertThat(approve(setup.approver().browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("APPROVED");
    }

    @Test
    void aProcessWithAWaitingRequestCannotBeRemovedAndOnceItEndedItCanAndTheRequestStillReads() {
        Setup setup = setup();
        String process = processExists(setup.admin(), "Expense", null, step("One", "ANY",
                user(setup.approver().userId())));
        String request = submitted(setup.submitter().browser(), record(setup.submitter().browser(),
                "{\"fieldA__c\":\"one\"}"), process);
        String path = DEFINITIONS + "/" + OBJECT + "/" + process;

        Response refused = setup.admin().request("DELETE", path, null);
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(names(setup.admin())).containsExactly(process);

        approve(setup.approver().browser(), request);
        Response removed = setup.admin().request("DELETE", path, null);

        assertThat(removed.status()).as(removed.body()).isEqualTo(204);
        assertThat(names(setup.admin())).isEmpty();
        Response history = instance(setup.admin(), request);
        assertThat(history.status()).as("the request outlives its process").isEqualTo(200);
        assertThat(JsonPath.<String>read(history.body(), "$.data.summary.processLabel")).isEqualTo("Expense");
    }

    @Test
    void anObjectWithAWaitingRequestCannotBeRemoved() {
        Setup setup = setup();
        String process = processExists(setup.admin(), "Expense", null, step("One", "ANY",
                user(setup.approver().userId())));
        submitted(setup.submitter().browser(), record(setup.submitter().browser(), "{\"fieldA__c\":\"one\"}"),
                process);

        Response refused = setup.admin().request("DELETE", METADATA_OBJECTS + "/" + OBJECT, null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
    }

    // ---- change sets and releases ----

    @Test
    void aChangeSetPublishesAProcessAndTheRollbackRemovesItAgain() {
        Setup setup = setup();
        Response set = setup.admin().postJson(SETS, "{\"name\":\"Approvals\",\"description\":\"\"}");
        String id = JsonPath.read(set.body(), "$.data.id");
        Response added = setup.admin().postJson(SETS + "/" + id + "/changes", "{\"kind\":"
                + "\"CREATE_APPROVAL_PROCESS\",\"objectApiName\":\"" + OBJECT + "\",\"createApprovalProcess\":"
                + createRequest("Planned", null, false, null, null,
                        step("One", "ANY", user(setup.approver().userId()))) + "}");
        Response checked = setup.admin().postJson(SETS + "/" + id + "/validate", "{}");
        assertThat(names(setup.admin())).as("a draft changes nothing").isEmpty();

        Response published = setup.admin().postJson(SETS + "/" + id + "/publish", "{}");
        assertThat(names(setup.admin())).containsExactly("Planned__c");
        Response undone = setup.admin().postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(names(setup.admin())).isEmpty();
        assertThat(JsonPath.<List<String>>read(setup.admin().get(RELEASES).body(), "$.data[0].items[*].kind"))
                .contains("APPROVAL_PROCESS");
    }

    @Test
    void aReleaseThatWouldRemoveAProcessWithAWaitingRequestIsRefusedWithTheReason() {
        Setup setup = setup();
        String process = processExists(setup.admin(), "Expense", null, step("One", "ANY",
                user(setup.approver().userId())));
        assertThat(process).isEqualTo("Expense__c");
        submitted(setup.submitter().browser(), record(setup.submitter().browser(), "{\"fieldA__c\":\"one\"}"),
                process);

        Response undone = setup.admin().postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(undone.status()).as("the latest release made the process: undoing it would lose a waiting "
                + "request").isEqualTo(409);
        assertThat(names(setup.admin())).containsExactly(process);
    }

    // ---- the catalogue ----

    @Test
    void theCatalogueTellsTheScreensWhatAProcessCanBeMadeOf() {
        Setup setup = setup();

        Response catalogue = setup.admin().get(ApprovalSupport.DEFINITIONS + "/catalogue");

        assertThat(catalogue.status()).as(catalogue.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.approverKinds[*].kind")).contains("USER",
                "PROFILE", "ROLE", "GROUP", "OWNER", "RECORD_USER", "OWNER_ROLE_MANAGER");
        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.completions[*].code")).containsExactly("ANY",
                "ALL");
    }
}

package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * What an approval sets on the record when it ends (ADR-0149): literals only, through the same write pipeline as a
 * person's write, in a savepoint after the status is ended. A refusal of the pipeline does not undo the decision the
 * people took: the record is unlocked, the request shows the stable reason, the history says so, and an administrator
 * can
 * make the updates again (explicitly, audited, against the version of the record they read).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalOutcomeIT extends ApprovalSupport {

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person approver,
            String process) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, createRequest("Outcome", null, false, null,
                outcomes(assignment("fieldC__c", "Approved"), assignment("fieldB__c", "Rejected"),
                        assignment("fieldB__c", "Recalled")),
                step("Only", "ANY", user(approver.userId()))));
        return new Setup(organization, admin, submitter, approver, process);
    }

    private static void rule(TestBrowser admin, String name, String expression) {
        Response created = admin.postJson(METADATA_OBJECTS + "/" + OBJECT + "/validation-rules", "{\"name\":\""
                + name + "\",\"label\":\"" + name + "\",\"expression\":\"" + expression + "\",\"errorMessage\":"
                + "\"RULE-MESSAGE-TEXT\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    // ---- applied ----

    @Test
    void anApprovalSetsItsLiteralsInTheSameTransactionAsTheDecisionAndBumpsTheVersionOnce() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());
        long before = recordVersion(setup.admin(), record);

        assertThat(approve(setup.approver().browser(), request).status()).isEqualTo(200);

        assertThat(fieldOf(setup.admin(), record, "fieldC__c")).isEqualTo("Approved");
        assertThat(fieldOf(setup.admin(), record, "fieldA__c")).as("only what the process names").isEqualTo("trip");
        assertThat(recordVersion(setup.admin(), record)).as("one write").isEqualTo(before + 1);
        assertThat(historyOf(setup.admin(), request)).contains("OUTCOME_APPLIED").doesNotContain("OUTCOME_FAILED");
        assertThat(optional(instance(setup.admin(), request).body(), "$.data.summary.outcomeFailure")).isNull();
    }

    @Test
    void aRejectionAndARecallSetTheirOwnLiterals() {
        Setup setup = setup();
        String rejected = record(setup.submitter().browser(), "{\"fieldA__c\":\"one\"}");
        String recalled = record(setup.submitter().browser(), "{\"fieldA__c\":\"two\"}");
        String first = submitted(setup.submitter().browser(), rejected, setup.process());
        String second = submitted(setup.submitter().browser(), recalled, setup.process());

        assertThat(reject(setup.approver().browser(), first, "No").status()).isEqualTo(200);
        assertThat(decide(setup.submitter().browser(), second, "recall", null).status()).isEqualTo(200);

        assertThat(fieldOf(setup.admin(), rejected, "fieldB__c")).isEqualTo("Rejected");
        assertThat(fieldOf(setup.admin(), recalled, "fieldB__c")).isEqualTo("Recalled");
    }

    @Test
    void anOutcomeIsTheSameWriteAPersonWouldHaveMade() {
        Setup setup = setup();
        String viaApproval = record(setup.submitter().browser(), "{\"fieldA__c\":\"same\"}");
        String byHand = record(setup.submitter().browser(), "{\"fieldA__c\":\"same\"}");
        long handBefore = recordVersion(setup.admin(), byHand);
        String request = submitted(setup.submitter().browser(), viaApproval, setup.process());

        assertThat(approve(setup.approver().browser(), request).status()).isEqualTo(200);
        assertThat(change(setup.submitter().browser(), byHand, "{\"fieldC__c\":\"Approved\"}").status())
                .isEqualTo(200);

        assertThat(fieldOf(setup.admin(), viaApproval, "fieldC__c")).isEqualTo(fieldOf(setup.admin(), byHand,
                "fieldC__c"));
        assertThat(recordVersion(setup.admin(), byHand)).isEqualTo(handBefore + 1);
        assertThat(scalar(setup.organization(), "select count(*) from outbox_event where event_type = "
                + "'record.updated'")).as("each write announces itself like any other").isGreaterThanOrEqualTo(2);
    }

    // ---- refused ----

    @Test
    void aRefusedOutcomeKeepsTheDecisionUnlocksTheRecordAndSaysWhyInStableWords() {
        Setup setup = setup();
        rule(setup.admin(), "NoApproved", "fieldC__c = 'Approved'");
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());

        Response approved = approve(setup.approver().browser(), request);

        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(statusOf(setup.admin(), request)).as("the people decided").isEqualTo("APPROVED");
        assertThat(JsonPath.<String>read(approved.body(), "$.data.summary.outcomeFailure"))
                .isEqualTo("rejected_by_rule");
        assertThat(optional(setup.admin().get(RECORDS + "/" + record).body(), "$.data.fields.fieldC__c"))
                .as("nothing was set").isNull();
        assertThat(historyOf(setup.admin(), request)).contains("OUTCOME_FAILED").doesNotContain("OUTCOME_APPLIED");
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"free\"}").status())
                .as("the record is free").isEqualTo(200);
        assertThat(approved.body()).doesNotContain("RULE-MESSAGE-TEXT");
        assertThat(JsonPath.<Boolean>read(instance(setup.admin(), request).body(), "$.data.actions.canReapply"))
                .isTrue();
        assertThat(JsonPath.<Boolean>read(instance(setup.approver().browser(), request).body(),
                "$.data.actions.canReapply")).isFalse();
    }

    // ---- made again ----

    @Test
    void anAdministratorMakesTheUpdatesAgainAfterTheCauseIsGoneAndItIsAuditedAndRecorded() {
        Setup setup = setup();
        rule(setup.admin(), "NoApproved", "fieldC__c = 'Approved'");
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());
        approve(setup.approver().browser(), request);
        assertThat(setup.admin().request("DELETE", METADATA_OBJECTS + "/" + OBJECT + "/validation-rules/NoApproved__c",
                null).status()).isBetween(200, 204);

        Response again = setup.admin().postJson(INSTANCES + "/" + request + "/outcome", "{\"recordVersion\":"
                + recordVersion(setup.admin(), record) + ",\"version\":" + instanceVersion(setup.admin(), request)
                + "}");

        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(fieldOf(setup.admin(), record, "fieldC__c")).isEqualTo("Approved");
        assertThat(optional(again.body(), "$.data.summary.outcomeFailure")).isNull();
        assertThat(historyOf(setup.admin(), request)).contains("OUTCOME_FAILED", "OUTCOME_REAPPLIED");
        assertThat(audited(setup.organization(), "approval.outcome.reapplied")).isEqualTo(1);
        assertThat(setup.admin().postJson(INSTANCES + "/" + request + "/outcome", "{\"recordVersion\":0,\"version\":"
                + instanceVersion(setup.admin(), request) + "}").status()).as("nothing left to make again")
                .isEqualTo(409);
    }

    @Test
    void makingTheUpdatesAgainIsRefusedForAStaleRecordForAMemberWithoutTheAbilityAndForANewerRequest() {
        Setup setup = setup();
        rule(setup.admin(), "NoApproved", "fieldC__c = 'Approved'");
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"trip\"}");
        String request = submitted(setup.submitter().browser(), record, setup.process());
        approve(setup.approver().browser(), request);
        setup.admin().request("DELETE", METADATA_OBJECTS + "/" + OBJECT + "/validation-rules/NoApproved__c", null);
        long requestVersion = instanceVersion(setup.admin(), request);

        Response member = setup.approver().browser().postJson(INSTANCES + "/" + request + "/outcome",
                "{\"recordVersion\":" + recordVersion(setup.admin(), record) + ",\"version\":" + requestVersion + "}");
        Response stale = setup.admin().postJson(INSTANCES + "/" + request + "/outcome",
                "{\"recordVersion\":999,\"version\":" + requestVersion + "}");
        submitted(setup.submitter().browser(), record, setup.process());
        Response newer = setup.admin().postJson(INSTANCES + "/" + request + "/outcome", "{\"recordVersion\":"
                + recordVersion(setup.admin(), record) + ",\"version\":" + requestVersion + "}");

        assertThat(member.status()).isEqualTo(403);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(newer.status()).as(newer.body()).isEqualTo(409);
        assertThat(fieldOf(setup.admin(), record, "fieldA__c")).isEqualTo("trip");
    }
}

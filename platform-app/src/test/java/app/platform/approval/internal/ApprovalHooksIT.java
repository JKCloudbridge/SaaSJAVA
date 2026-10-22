package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.approval.ApprovalStartException;
import app.platform.approval.ApprovalStartException.Reason;
import app.platform.approval.Approvals;
import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWrites;
import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hooks of the approval engine (FA4A, ADR-0187), driven through the real API and the real database with a hook of
 * the sample object that numbers a record at the first approval of the first step. The exit criterion: the hook numbers
 * a record at the first approval and nobody else can write it while the request is pending. Around it: the lock lets
 * through only the owner of the lock, a refusal leaves nothing, a failure undoes the whole decision and tells nothing,
 * and the end of a request is announced once however it comes.
 */
@PlatformIntegrationTest
@Import(ApprovalTestHooks.class)
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalHooksIT extends ApprovalSupport {

    private static final String NUMBER = ApprovalTestHooks.NUMBER_FIELD;
    private static final String COUNTER = "select coalesce(max(next_value), 0) from number_series_counter "
            + "where series_key = 'test-hook'";

    @Autowired
    private ApprovalTestHooks.Control control;

    @Autowired
    private Approvals approvals;

    @Autowired
    private BackgroundWrites writes;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void resetTheHook() {
        control.reset();
    }

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person first, Person second) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        return new Setup(organization, admin, person(organization, admin), person(organization, admin),
                person(organization, admin));
    }

    private String twoSteps(Setup setup, String name) {
        return processExists(setup.admin(), name, null, step("First", "ANY", user(setup.first().userId())),
                step("Second", "ANY", user(setup.second().userId())));
    }

    private <T> T asPlatform(Organization organization, Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private static long calls(List<String> seen, String prefix) {
        return seen.stream().filter(call -> call.startsWith(prefix)).count();
    }

    // ---- the exit criterion ----

    @Test
    void aHookNumbersTheRecordAtTheFirstApprovalAndNobodyElseCanWriteItWhilePending() {
        Setup setup = setup();
        String process = twoSteps(setup, "Numbered");
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, process);

        // The hook was told the identifier the request got, before the request existed.
        assertThat(control.calls).containsExactly("submit:" + request);
        assertThat(optional(setup.admin().get(RECORDS + "/" + record).body(), "$.data.fields." + NUMBER)).isNull();

        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);

        assertThat(fieldOf(setup.admin(), record, NUMBER)).isEqualTo("N-1");
        assertThat(scalar(setup.organization(), COUNTER)).isEqualTo(2);

        // A member without the override cannot write the number, or anything else, while the request is pending.
        Response typed = change(setup.submitter().browser(), record, "{\"" + NUMBER + "\":\"typed\"}");
        assertThat(typed.status()).isEqualTo(409);
        assertThat(code(typed)).isEqualTo("RECORD_LOCKED");
        // A member who holds the override ability still edits another field, and it is audited.
        assertThat(change(setup.admin(), record, "{\"fieldA__c\":\"corrected\"}").status()).isEqualTo(200);
        assertThat(audited(setup.organization(), "approval.lock.overridden")).isEqualTo(1);
        // A workflow, and the outcome update of an approval, are refused the same way.
        assertThatThrownBy(() -> asPlatform(setup.organization(), () -> writes.update(
                new BackgroundWrites.Source("Flow__c", UUID.randomUUID(), null), OBJECT, UUID.fromString(record),
                List.of(assignmentOf("fieldB__c", "by a workflow")))))
                .isInstanceOfSatisfying(BackgroundWriteException.class,
                        refused -> assertThat(refused.reason().key()).isEqualTo("record_locked"));
        // The source of a hook is accepted only for the request that owns the lock.
        assertThatThrownBy(() -> asPlatform(setup.organization(), () -> writes.update(
                BackgroundWrites.Source.approvalHook(process, UUID.randomUUID(), null), OBJECT,
                UUID.fromString(record), List.of(assignmentOf("fieldB__c", "by another request")))))
                .isInstanceOf(BackgroundWriteException.class);
        asPlatform(setup.organization(), () -> writes.update(
                BackgroundWrites.Source.approvalHook(process, UUID.fromString(request), null), OBJECT,
                UUID.fromString(record), List.of(assignmentOf("fieldB__c", "by the owner"))));
        assertThat(fieldOf(setup.admin(), record, "fieldB__c")).isEqualTo("by the owner");

        // The second approver finds the number; the request ends approved, announced once; the number stays.
        assertThat(approve(setup.second().browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("APPROVED");
        assertThat(fieldOf(setup.admin(), record, NUMBER)).isEqualTo("N-1");
        assertThat(scalar(setup.organization(), COUNTER)).isEqualTo(2);
        assertThat(control.calls).containsExactly("submit:" + request, "approve:" + request + ":1",
                "approve:" + request + ":2", "ended:" + request + ":APPROVED");
        // The lock is gone with the request.
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"again\"}").status()).isEqualTo(200);
    }

    private static BackgroundWrites.Assignment assignmentOf(String field, String text) {
        return new BackgroundWrites.Assignment(field, new BackgroundWrites.Value.Literal(text));
    }

    @Test
    void whenEveryoneMustApproveTheFirstApprovalNumbersAndTheLaterOnesFindTheNumber() {
        Setup setup = setup();
        String process = processExists(setup.admin(), "Everyone", null,
                step("First", "ALL", user(setup.first().userId()), user(setup.second().userId())));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, process);

        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);
        assertThat(fieldOf(setup.admin(), record, NUMBER)).isEqualTo("N-1");
        assertThat(approve(setup.second().browser(), request).status()).isEqualTo(200);

        assertThat(fieldOf(setup.admin(), record, NUMBER)).isEqualTo("N-1");
        assertThat(scalar(setup.organization(), COUNTER)).isEqualTo(2);
        assertThat(calls(control.calls, "approve:")).isEqualTo(2);
    }

    @Test
    void aRejectionAtTheFirstStepLeavesNoNumberAndTakesNoneFromTheCounter() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, twoSteps(setup, "Rejected"));

        assertThat(reject(setup.first().browser(), request, "No.").status()).isEqualTo(200);

        assertThat(optional(setup.admin().get(RECORDS + "/" + record).body(), "$.data.fields." + NUMBER)).isNull();
        assertThat(scalar(setup.organization(), COUNTER)).isZero();
        assertThat(calls(control.calls, "approve:")).isZero();
        assertThat(control.calls).contains("ended:" + request + ":REJECTED");
    }

    // ---- a refusal leaves nothing ----

    @Test
    void aHookThatRefusesTheSubmitLeavesNoRequestAndTheRecordStaysOpen() {
        Setup setup = setup();
        String process = twoSteps(setup, "Refused");
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        control.refuseSubmit = true;

        Response refused = submit(setup.submitter().browser(), record, process, null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).isEqualTo(
                ApprovalTestHooks.REFUSAL_WORDS);
        assertThat(scalar(setup.organization(), "select count(*) from approval_instance")).isZero();
        assertThat(change(setup.submitter().browser(), record, "{\"fieldA__c\":\"y\"}").status()).isEqualTo(200);
        // The refusal is audited by its stable key, in a record that outlives the transaction it refused.
        assertThat(audited(setup.organization(), "approval.hook.refused")).isEqualTo(1);
        assertThat(owner("select attributes::text from audit_record where event_type = 'approval.hook.refused' "
                + "and context_tenant_id = ?", setup.organization().id().value()).get(0)).contains("not_ready")
                .doesNotContain(ApprovalTestHooks.REFUSAL_WORDS);
    }

    @Test
    void aPlatformStartThatTheHookRefusesFailsWithAStableReasonAndLocksNothing() {
        Setup setup = setup();
        String process = twoSteps(setup, "Started");
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        control.refuseSubmit = true;

        assertThatThrownBy(() -> asPlatform(setup.organization(),
                () -> approvals.startByPlatform(OBJECT, UUID.fromString(record), process, null)))
                .isInstanceOfSatisfying(ApprovalStartException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Reason.HOOK_REFUSED));
        assertThat(scalar(setup.organization(), "select count(*) from approval_instance")).isZero();

        control.refuseSubmit = false;
        Approvals.Started started = asPlatform(setup.organization(),
                () -> approvals.startByPlatform(OBJECT, UUID.fromString(record), process, null));
        assertThat(started.alreadyPending()).isFalse();
        assertThat(control.calls).contains("submit:" + started.requestId());
    }

    @Test
    void aHookThatRefusesAnApprovalLeavesTheTaskOpenAndEverythingAsItWas() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, twoSteps(setup, "NotReady"));
        List<String> history = historyOf(setup.admin(), request);
        control.refuseApprove = true;

        Response refused = approve(setup.first().browser(), request);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).isEqualTo(
                ApprovalTestHooks.REFUSAL_WORDS);
        assertThat(openRequests(setup.first().browser())).contains(request);
        assertThat(historyOf(setup.admin(), request)).isEqualTo(history);
        assertThat(statusOf(setup.admin(), request)).isEqualTo("PENDING");
        assertThat(optional(setup.admin().get(RECORDS + "/" + record).body(), "$.data.fields." + NUMBER)).isNull();
        assertThat(scalar(setup.organization(), COUNTER)).isZero();
    }

    // ---- a failure undoes the whole decision and tells nothing ----

    @Test
    void aHookThatFailsAfterWritingUndoesTheWriteTheCounterAndTheDecisionAndTellsNothing() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, twoSteps(setup, "Failing"));
        List<String> history = historyOf(setup.admin(), request);
        control.failAfterWriting = true;

        Response failed = approve(setup.first().browser(), request);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body()).doesNotContain("SECRET").doesNotContain("4711");
        assertThat(optional(setup.admin().get(RECORDS + "/" + record).body(), "$.data.fields." + NUMBER)).isNull();
        assertThat(scalar(setup.organization(), COUNTER)).isZero();
        assertThat(historyOf(setup.admin(), request)).isEqualTo(history);
        assertThat(openRequests(setup.first().browser())).contains(request);

        // Once the hook works again the same person approves and the record gets the first number.
        control.failAfterWriting = false;
        assertThat(approve(setup.first().browser(), request).status()).isEqualTo(200);
        assertThat(fieldOf(setup.admin(), record, NUMBER)).isEqualTo("N-1");
    }

    @Test
    void aHookThatFailsAtTheEndLeavesTheRequestPendingWithNothingApplied() {
        Setup setup = setup();
        String process = processExists(setup.admin(), createRequest("OneStep", null, false, null,
                outcomes(assignment("fieldB__c", "Approved"), assignment("fieldB__c", "Rejected"),
                        assignment("fieldB__c", "Recalled")),
                step("Only", "ANY", user(setup.first().userId()))));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(setup.submitter().browser(), record, process);
        control.failEnded = true;

        Response failed = approve(setup.first().browser(), request);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body()).doesNotContain("SECRET");
        assertThat(statusOf(setup.admin(), request)).isEqualTo("PENDING");
        assertThat(optional(setup.admin().get(RECORDS + "/" + record).body(), "$.data.fields." + NUMBER)).isNull();
        assertThat(optional(setup.admin().get(RECORDS + "/" + record).body(), "$.data.fields.fieldB__c")).isNull();
        assertThat(scalar(setup.organization(), COUNTER)).isZero();
    }

    // ---- the end is announced once, however it comes ----

    @Test
    void theEndIsAnnouncedOncePerRequestForApprovedRejectedRecalledAndCancelled() {
        Setup setup = setup();
        String process = processExists(setup.admin(), createRequest("Ends", null, false, null,
                outcomes(assignment("fieldB__c", "Approved"), assignment("fieldB__c", "Rejected"),
                        assignment("fieldB__c", "Recalled")),
                step("Only", "ANY", user(setup.first().userId()))));
        TestBrowser mine = setup.submitter().browser();
        String approved = submitted(mine, record(mine, "{\"fieldA__c\":\"a\"}"), process);
        String rejected = submitted(mine, record(mine, "{\"fieldA__c\":\"b\"}"), process);
        String recalled = submitted(mine, record(mine, "{\"fieldA__c\":\"c\"}"), process);
        String cancelled = submitted(mine, record(mine, "{\"fieldA__c\":\"d\"}"), process);

        assertThat(approve(setup.first().browser(), approved).status()).isEqualTo(200);
        assertThat(reject(setup.first().browser(), rejected, "No.").status()).isEqualTo(200);
        assertThat(decide(mine, recalled, "recall", null).status()).isEqualTo(200);
        assertThat(decide(setup.admin(), cancelled, "cancel", null).status()).isEqualTo(200);

        assertThat(control.calls.stream().filter(call -> call.startsWith("ended:")).toList()).containsExactlyInAnyOrder(
                "ended:" + approved + ":APPROVED", "ended:" + rejected + ":REJECTED",
                "ended:" + recalled + ":RECALLED", "ended:" + cancelled + ":CANCELLED");
    }

    @Test
    void nothingAHookHandlesReachesALogAnAuditRecordAnEventAnOutboxPayloadOrAMail() {
        Setup setup = setup();
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"PRIVATE-TEXT\"}");
        String process = twoSteps(setup, "Quiet");
        try (LogCapture logs = LogCapture.of("app.platform")) {
            control.refuseSubmit = true;
            assertThat(submit(setup.submitter().browser(), record, process, "PRIVATE-COMMENT").status())
                    .isEqualTo(409);
            control.refuseSubmit = false;
            String request = submitted(setup.submitter().browser(), record, process);
            control.failAfterWriting = true;
            assertThat(approve(setup.first().browser(), request).status()).isEqualTo(500);
            control.failAfterWriting = false;
            control.refuseApprove = true;
            assertThat(approve(setup.first().browser(), request).status()).isEqualTo(409);
            control.refuseApprove = false;
            assertThat(decide(setup.first().browser(), request, "approve", "PRIVATE-COMMENT").status())
                    .isEqualTo(200);
            assertThat(approve(setup.second().browser(), request).status()).isEqualTo(200);
            settle(setup.organization());

            String logged = logs.events().stream().map(event -> event.getFormattedMessage() + " "
                    + event.getKeyValuePairs()).reduce("", String::concat);
            assertThat(logged).doesNotContain("SECRET").doesNotContain("4711").doesNotContain("N-1")
                    .doesNotContain("PRIVATE-").doesNotContain(ApprovalTestHooks.REFUSAL_WORDS);
        }
        String audits = String.join(" ", owner("select attributes::text from audit_record "
                + "where context_tenant_id = ?", setup.organization().id().value()));
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                setup.organization().id().value()));
        String mails = String.join(" ", owner("select variables::text from mail_queue"));
        for (String where : List.of(audits, events, mails)) {
            assertThat(where).doesNotContain("SECRET").doesNotContain("N-1").doesNotContain("PRIVATE-")
                    .doesNotContain(ApprovalTestHooks.REFUSAL_WORDS);
        }
        assertThat(audits).contains("not_ready");
        assertThat(fieldOf(setup.admin(), record, NUMBER)).isEqualTo("N-1");
    }

    @Test
    void anOrganizationWithoutApprovalsNeverCallsTheHook() {
        Organization organization = organizationWithoutTheFeature();
        TestBrowser admin = adminOf(organization);
        String record = record(admin, "{\"fieldA__c\":\"x\"}");

        Response refused = submit(admin, record, "Anything__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(control.calls).isEmpty();
    }

    @Test
    void anUnflaggedStepWithNobodyBehavesAsBeforeAHookNotWithstanding() {
        Setup setup = setup();
        Response group = setup.admin().postJson("/api/v1/groups", "{\"name\":\"empty-" + System.nanoTime()
                + "\",\"description\":\"\"}");
        String empty = JsonPath.read(group.body(), "$.data.id");
        String process = processExists(setup.admin(), "Nobody", null, step("Only", "ANY", group(empty)));
        String record = record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}");

        Response refused = submit(setup.submitter().browser(), record, process, null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(control.calls).isEmpty();
    }
}

package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.approval.ApprovalDecisions;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks.Choice;
import app.platform.sharedkernel.approval.ApprovalHooks.DecisionContext;
import app.platform.sharedkernel.approval.ApprovalHooks.EndContext;
import app.platform.sharedkernel.approval.ApprovalHooks.SubmitContext;
import app.platform.sharedkernel.approval.ApprovalOutcome;
import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetMoveRefusal;
import app.platform.sheets.SheetStatus;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * What the approval engine asks of the cluster for a change request (FA8, ADR-0202), with fakes and no database: a
 * process
 * whose last step may be skipped is refused; a step that asks for a choice keeps the suggestion; the last approval
 * needs
 * the Publisher policy and the revised copy, and does everything in one order; any other end declines the request and
 * ends
 * the copy unused; a recall changes nothing. The audience and the words of a refusal are fixed.
 */
class ChangeRequestApprovalHooksTest {

    private static final RecordSet SET = RecordSetsFixture.QMS;
    private static final String OBJECT = SET.request();
    private static final String TEMPLATE_OBJECT = SET.template();
    private static final UUID REVIEW = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID CHANGE = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final UUID ASKER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private ChangeRequestStore store;
    private ChangeRequestHeaders headers;
    private ChangeRequestChanges changes;
    private ChangeRequestNotices notices;
    private BackgroundRecords records;
    private SheetLifecycle sheets;
    private RegisterStore registers;
    private TemplatePublishing publishing;
    private AudienceSuggestions suggestions;
    private DepartmentAudiences audiences;
    private ApprovalDecisions decisions;
    private Permissions permissions;
    private TenantContexts contexts;
    private ChangeRequestApprovalHooks hooks;

    @BeforeEach
    void fakes() {
        store = mock(ChangeRequestStore.class);
        headers = mock(ChangeRequestHeaders.class);
        changes = mock(ChangeRequestChanges.class);
        notices = mock(ChangeRequestNotices.class);
        records = mock(BackgroundRecords.class);
        sheets = mock(SheetLifecycle.class);
        registers = mock(RegisterStore.class);
        publishing = mock(TemplatePublishing.class);
        suggestions = mock(AudienceSuggestions.class);
        audiences = mock(DepartmentAudiences.class);
        decisions = mock(ApprovalDecisions.class);
        permissions = mock(Permissions.class);
        contexts = mock(TenantContexts.class);
        hooks = new ChangeRequestApprovalHooks(store, headers, changes, notices, records, sheets, registers,
                publishing, suggestions, audiences, decisions, permissions, contexts, List.of(),
                RecordSetsFixture.both());
        when(contexts.current()).thenReturn(Optional.of(new TenantContext(new TenantId(UUID.randomUUID()), APPROVER,
                MEMBERSHIP)));
        when(permissions.has(MEMBERSHIP, Ability.SHEETS_PUBLISH)).thenReturn(true);
        when(records.find(eq(TEMPLATE_OBJECT), eq(TEMPLATE))).thenReturn(Optional.of(template()));
        when(decisions.approvalsOf(REQUEST)).thenReturn(List.of());
        when(audiences.offered()).thenReturn(Map.of("PLANT1", List.of("PRD", "QLT")));
        when(store.findByHeader(REVIEW)).thenReturn(Optional.of(row(ChangeRequestStatus.OPEN)));
        when(store.answer(any(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    private static DataRecord template() {
        return new DataRecord(TEMPLATE, TEMPLATE_OBJECT, null, OWNER, Map.of("name", "Typed-by-an-author"), 0,
                Instant.EPOCH, ASKER, Instant.EPOCH, ASKER, null, null);
    }

    private static RequestRow row(ChangeRequestStatus status) {
        return new RequestRow(CHANGE, TEMPLATE, 1, "Title", status.name(), null, null, null, null, 0, Instant.EPOCH,
                ASKER, null, REVIEW, TEMPLATE_OBJECT);
    }

    private static List<Choice> publishChoice() {
        return List.of(new Choice("location", "PLANT1"), new Choice("department", "PLANT1/PRD"),
                new Choice("option", "allowDownload"));
    }

    private static DecisionContext deciding(boolean asks, boolean ends, List<Choice> choices) {
        return new DecisionContext(REQUEST, OBJECT, REVIEW, "process", 1, 0, ends ? 3 : 2, "Step", APPROVER, asks,
                choices, ends);
    }

    private static EndContext ended(ApprovalOutcome outcome) {
        return new EndContext(REQUEST, OBJECT, REVIEW, "process", 1, 0, outcome, APPROVER);
    }

    private ApprovalHookRefusal refused(DecisionContext context) {
        return org.junit.jupiter.api.Assertions.assertThrows(ApprovalHookRefusal.class,
                () -> hooks.beforeApprove(context, null));
    }

    // ---- when the request starts ----

    @Test
    void aProcessWhoseLastStepMayBeSkippedIsRefusedBecauseTheLastStepPublishes() {
        SubmitContext skippable = new SubmitContext(REQUEST, OBJECT, REVIEW, "process", 1, ASKER, true);

        ApprovalHookRefusal refusal = org.junit.jupiter.api.Assertions.assertThrows(ApprovalHookRefusal.class,
                () -> hooks.onSubmit(skippable));

        assertThat(refusal.reason()).isEqualTo("last_step_skippable");
        assertThat(refusal.words()).isEqualTo(ChangeRequestApprovalHooks.LAST_STEP_WORDS);
        verifyNoInteractions(sheets);
    }

    @Test
    void aRequestSentAgainPointsItsRevisedCopyAtTheNewApproval() {
        hooks.onSubmit(new SubmitContext(REQUEST, OBJECT, REVIEW, "process", 1, ASKER, false));

        verify(sheets).retargetHeldDraft(TEMPLATE_OBJECT, TEMPLATE, REQUEST, ASKER);
    }

    @Test
    void theFirstSubmitHasNoRequestYetAndNothingToPointAt() {
        when(store.findByHeader(REVIEW)).thenReturn(Optional.empty());

        hooks.onSubmit(new SubmitContext(REQUEST, OBJECT, REVIEW, "process", 1, ASKER, false));

        verifyNoInteractions(sheets);
    }

    // ---- an approval that does not end the request ----

    @Test
    void aStepThatAsksForAChoiceKeepsTheSuggestionForTheTemplateNotTheReviewRecord() {
        DecisionContext asking = deciding(true, false, List.of(new Choice("location", "PLANT1")));

        hooks.beforeApprove(asking, null);

        verify(suggestions).accept(TEMPLATE_OBJECT, asking, TEMPLATE);
        verifyNoInteractions(publishing);
    }

    @Test
    void aStepThatAsksForNothingDoesNothing() {
        hooks.beforeApprove(deciding(false, false, List.of()), null);

        verifyNoInteractions(suggestions, sheets, publishing, registers);
    }

    // ---- the last approval ----

    @Test
    void thePersonMustHoldThePublisherPolicyAndNothingChangesWithout() {
        when(permissions.has(MEMBERSHIP, Ability.SHEETS_PUBLISH)).thenReturn(false);

        ApprovalHookRefusal refusal = refused(deciding(false, true, publishChoice()));

        assertThat(refusal.reason()).isEqualTo("publisher_needed");
        assertThat(refusal.words()).isEqualTo(ChangeRequestApprovalHooks.NOT_PUBLISHER_WORDS);
        verifyNoInteractions(publishing, registers);
        verify(sheets, never()).submit(any(), any(), any(), any());
    }

    @Test
    void withoutTheRevisedCopyTheLastApprovalIsRefusedInFixedWords() {
        when(sheets.heldDraft(TEMPLATE_OBJECT, TEMPLATE)).thenReturn(Optional.empty());

        ApprovalHookRefusal refusal = refused(deciding(false, true, publishChoice()));

        assertThat(refusal.reason()).isEqualTo("no_revised_copy");
        assertThat(refusal.words()).isEqualTo(ChangeRequestApprovalHooks.NO_COPY_WORDS);
        verifyNoInteractions(publishing, registers);
    }

    @Test
    void aPlaceTheListsNoLongerOfferIsRefusedBeforeAnythingMoves() {
        when(sheets.heldDraft(TEMPLATE_OBJECT, TEMPLATE)).thenReturn(Optional.of(
                new SheetLifecycle.HeldDraft(3, REQUEST)));
        List<Choice> gone = List.of(new Choice("location", "PLANT1"), new Choice("department", "PLANT1/GONE"));

        ApprovalHookRefusal refusal = refused(deciding(false, true, gone));

        assertThat(refusal.reason()).isEqualTo("choice_not_offered");
        verify(sheets, never()).submit(any(), any(), any(), any());
    }

    @Test
    void aLocationWithoutADepartmentIsRefusedAsForATemplate() {
        when(sheets.heldDraft(TEMPLATE_OBJECT, TEMPLATE)).thenReturn(Optional.of(
                new SheetLifecycle.HeldDraft(3, REQUEST)));

        ApprovalHookRefusal refusal = refused(deciding(false, true, List.of(new Choice("location", "PLANT1"))));

        assertThat(refusal.reason()).isEqualTo("department_needed");
    }

    @Test
    void aRevisedCopyThatCannotBeSentIsRefusedWithTheSheetsOwnFixedWords() {
        when(sheets.heldDraft(TEMPLATE_OBJECT, TEMPLATE)).thenReturn(Optional.of(
                new SheetLifecycle.HeldDraft(3, REQUEST)));
        when(sheets.submit(TEMPLATE_OBJECT, TEMPLATE, REQUEST, APPROVER))
                .thenThrow(new SheetMoveRefusal(SheetMoveRefusal.Reason.EMPTY));

        ApprovalHookRefusal refusal = refused(deciding(false, true, publishChoice()));

        assertThat(refusal.reason()).isEqualTo("revised_copy_empty");
        verifyNoInteractions(publishing);
    }

    @Test
    void theLastApprovalSendsApprovesPublishesStampsAndFinishesInThatOrder() {
        when(sheets.heldDraft(TEMPLATE_OBJECT, TEMPLATE)).thenReturn(Optional.of(
                new SheetLifecycle.HeldDraft(3, REQUEST)));
        when(publishing.publishRevised(eq(SET), eq(TEMPLATE), eq(3), eq(APPROVER), any(), anyBoolean(),
                anyBoolean()))
                .thenReturn(new TemplatePublishing.Published(2, 2, 1));

        hooks.beforeApprove(deciding(false, true, publishChoice()), null);

        InOrder order = inOrder(sheets, publishing, registers, store, changes);
        order.verify(sheets).submit(TEMPLATE_OBJECT, TEMPLATE, REQUEST, APPROVER);
        order.verify(sheets).reviewed(TEMPLATE_OBJECT, TEMPLATE, REQUEST, SheetStatus.APPROVED, APPROVER);
        order.verify(publishing).publishRevised(eq(SET), eq(TEMPLATE), eq(3), eq(APPROVER),
                eq(List.of(new TemplateViews.AudiencePick("PLANT1", "PRD"))), eq(false), eq(true));
        order.verify(registers).stamp(eq(CHANGE), any(), eq(APPROVER));
        order.verify(store).answer(CHANGE, ChangeRequestStatus.OPEN, ChangeRequestStatus.DONE, null, 3, APPROVER);
        order.verify(changes).published(SET, APPROVER, TEMPLATE, CHANGE, 3, 2, 1);
    }

    @Test
    void theNewRevisionIsStampedAsTwoDigitsAndTheSignatureNamesTheDay() {
        when(sheets.heldDraft(TEMPLATE_OBJECT, TEMPLATE)).thenReturn(Optional.of(
                new SheetLifecycle.HeldDraft(3, REQUEST)));
        when(publishing.publishRevised(any(), any(), anyInt(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new TemplatePublishing.Published(1, 2, 1));

        hooks.beforeApprove(deciding(false, true, publishChoice()), null);

        org.mockito.ArgumentCaptor<RegisterStore.Stamp> stamp = org.mockito.ArgumentCaptor
                .forClass(RegisterStore.Stamp.class);
        verify(registers).stamp(eq(CHANGE), stamp.capture(), eq(APPROVER));
        assertThat(stamp.getValue().newRevNo()).isEqualTo("01");
        assertThat(stamp.getValue().signatureNote()).startsWith("Approved electronically on ");
        assertThat(stamp.getValue().approvedBy()).as("the person giving the last approval is named").isNotBlank();
    }

    // ---- when the request ends ----

    @Test
    void aRejectionDeclinesTheRequestForGoodAndEndsTheCopyAsRejected() {
        when(sheets.closeHeldDraft(TEMPLATE_OBJECT, TEMPLATE, REQUEST, SheetStatus.REJECTED, APPROVER)).thenReturn(3);

        hooks.onEnded(ended(ApprovalOutcome.REJECTED));

        verify(changes).copyDiscarded(SET, APPROVER, TEMPLATE, CHANGE, 3, "rejected");
        verify(store).answer(CHANGE, ChangeRequestStatus.OPEN, ChangeRequestStatus.DECLINED, null, null, APPROVER);
        verify(headers).status(SET, REVIEW, ChangeRequestStatus.DECLINED);
        verify(notices).tell(eq(SET), eq(ASKER), eq(APPROVER), eq(TEMPLATE), eq(CHANGE), eq("declined"), any());
    }

    @Test
    void anAdministratorsCancelEndsTheCopyAsWithdrawn() {
        hooks.onEnded(ended(ApprovalOutcome.CANCELLED));

        verify(sheets).closeHeldDraft(TEMPLATE_OBJECT, TEMPLATE, REQUEST, SheetStatus.WITHDRAWN, APPROVER);
        verify(headers).status(SET, REVIEW, ChangeRequestStatus.DECLINED);
    }

    @Test
    void aRecallLeavesTheRequestOpenWithItsCopy() {
        hooks.onEnded(ended(ApprovalOutcome.RECALLED));

        verifyNoInteractions(sheets, headers, notices);
        verify(store, never()).answer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anApprovedEndAfterThePublishOnlyFollowsOnTheRecordAndTellsPeople() {
        when(store.findByHeader(REVIEW)).thenReturn(Optional.of(row(ChangeRequestStatus.DONE)));

        hooks.onEnded(ended(ApprovalOutcome.APPROVED));

        verify(headers).status(SET, REVIEW, ChangeRequestStatus.DONE);
        verify(notices).tell(eq(SET), eq(ASKER), eq(APPROVER), eq(TEMPLATE), eq(CHANGE), eq("done"), any());
    }

    @Test
    void anApprovedEndThatWasNeverPublishedUndoesTheDecision() {
        assertThatThrownBy(() -> hooks.onEnded(ended(ApprovalOutcome.APPROVED)))
                .isInstanceOf(IllegalStateException.class);
        verify(headers, never()).status(any(), any(), any());
    }

    @Test
    void aReviewRecordWithoutARequestIsLeftAlone() {
        when(store.findByHeader(REVIEW)).thenReturn(Optional.empty());

        hooks.onEnded(ended(ApprovalOutcome.REJECTED));
        hooks.beforeApprove(deciding(false, true, publishChoice()), null);

        verifyNoInteractions(sheets, headers, publishing);
    }
}

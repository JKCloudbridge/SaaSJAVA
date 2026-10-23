package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks.EndContext;
import app.platform.sharedkernel.approval.ApprovalHooks.SubmitContext;
import app.platform.sharedkernel.approval.ApprovalOutcome;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillRefusal;
import app.platform.sheets.FillStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The hook of the approval engine for a fill-in (FF1, ADR-0192), with no database: the records, the cell table's
 * lifecycle and the writer of the header are fakes that remember what they were asked. What it must do: refuse a sent
 * fill-in of a retired template, turn the cell table's refusals into the engine's with fixed words, move the fill-in
 * and
 * the copy of its status together, map each way a review ends to the status it leaves, and make the whole decision fail
 * when the header cannot follow.
 */
class FillApprovalHooksTest {

    private static final UUID FILL = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();

    private final BackgroundRecords records = mock(BackgroundRecords.class);
    private final FillLifecycle fills = mock(FillLifecycle.class);
    private final FillHeaders headers = mock(FillHeaders.class);
    private final FillApprovalHooks hook = new FillApprovalHooks(records, fills, headers);

    private static DataRecord record(String object, UUID id, Map<String, Object> fields) {
        Instant now = Instant.now();
        return new DataRecord(id, object, null, PERSON, fields, 1, now, PERSON, now, PERSON, null, null);
    }

    private void headerOf(Object templateId) {
        when(records.find(Fills.OBJECT, FILL)).thenReturn(Optional.of(record(Fills.OBJECT, FILL,
                templateId == null ? Map.of() : Map.of(Fills.TEMPLATE_ID, templateId))));
    }

    private void template(boolean retired) {
        when(records.find(Templates.OBJECT, TEMPLATE)).thenReturn(Optional.of(record(Templates.OBJECT, TEMPLATE,
                Map.of(Templates.RETIRED, retired))));
    }

    private static SubmitContext submit() {
        return new SubmitContext(REQUEST, Fills.OBJECT, FILL, "FillReview", 1, PERSON);
    }

    private static EndContext ended(ApprovalOutcome outcome) {
        return new EndContext(REQUEST, Fills.OBJECT, FILL, "FillReview", 1, 3, outcome, PERSON);
    }

    // ---- on submit ----

    @Test
    void itServesTheFillInObjectAndWritesNothingThroughTheEnginesWriter() {
        assertThat(hook.object()).isEqualTo("InspectionFill");
        assertThat(hook.writes()).isEmpty();
    }

    @Test
    void aSubmitMovesTheFillInToReviewAndTheCopyOnTheHeaderFollows() {
        headerOf(TEMPLATE.toString());
        template(false);

        hook.onSubmit(submit());

        verify(fills).submit(Fills.OBJECT, FILL, REQUEST, PERSON);
        verify(headers).status(FILL, FillStatus.SUBMITTED);
    }

    @Test
    void aSubmitOfAFillInOfARetiredTemplateIsRefusedInFixedWordsAndMovesNothing() {
        headerOf(TEMPLATE.toString());
        template(true);

        assertThatThrownBy(() -> hook.onSubmit(submit())).isInstanceOfSatisfying(ApprovalHookRefusal.class,
                refusal -> {
                    assertThat(refusal.reason()).isEqualTo("template_retired");
                    assertThat(refusal.words()).contains("retired");
                });
        verify(fills, never()).submit(any(), any(), any(), any());
        verify(headers, never()).status(any(), any());
    }

    @Test
    void aHeaderThatNamesNoTemplateThatExistsIsRefusedLikeARetiredOne() {
        headerOf("not-an-identifier");

        assertThatThrownBy(() -> hook.onSubmit(submit())).isInstanceOfSatisfying(ApprovalHookRefusal.class,
                refusal -> assertThat(refusal.reason()).isEqualTo("template_retired"));

        headerOf(TEMPLATE.toString());
        when(records.find(Templates.OBJECT, TEMPLATE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> hook.onSubmit(submit())).isInstanceOf(ApprovalHookRefusal.class);
        verify(fills, never()).submit(any(), any(), any(), any());
    }

    @Test
    void aHeaderThatIsGoneIsRefused() {
        when(records.find(Fills.OBJECT, FILL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> hook.onSubmit(submit())).isInstanceOfSatisfying(ApprovalHookRefusal.class,
                refusal -> assertThat(refusal.reason()).isEqualTo("fill_missing"));
    }

    @ParameterizedTest
    @CsvSource({"NO_ROWS,fill_no_rows", "INCOMPLETE,fill_incomplete", "NOT_FILLABLE,fill_not_fillable",
        "NOT_THE_FILLER,fill_not_the_filler", "VERSION_RETIRED,fill_version_retired"})
    void theCellTablesRefusalBecomesTheEnginesWithAStableKeyAndTheSameWords(String reason, String key) {
        headerOf(TEMPLATE.toString());
        template(false);
        FillRefusal.Reason found = FillRefusal.Reason.valueOf(reason);
        FillRefusal refusal = reason.equals("INCOMPLETE") ? new FillRefusal(found, "cells.rows.r2.cells.t")
                : new FillRefusal(found);
        doThrow(refusal).when(fills).submit(any(), any(), any(), any());

        assertThatThrownBy(() -> hook.onSubmit(submit())).isInstanceOfSatisfying(ApprovalHookRefusal.class, thrown -> {
            assertThat(thrown.reason()).isEqualTo(key);
            assertThat(thrown.words()).isEqualTo(refusal.getMessage());
        });
        verify(headers, never()).status(any(), any());
    }

    @Test
    void aSubmitByThePlatformPassesNoPerson() {
        headerOf(TEMPLATE.toString());
        template(false);

        hook.onSubmit(new SubmitContext(REQUEST, Fills.OBJECT, FILL, "FillReview", 1, null));

        verify(fills).submit(Fills.OBJECT, FILL, REQUEST, null);
    }

    // ---- when the request ends ----

    @ParameterizedTest
    @CsvSource({"APPROVED,APPROVED", "REJECTED,RETURNED", "RECALLED,DRAFT", "CANCELLED,DRAFT"})
    void eachWayAReviewEndsLeavesTheStatusItShould(ApprovalOutcome outcome, FillStatus status) {
        when(fills.ended(Fills.OBJECT, FILL, REQUEST, status, PERSON)).thenReturn(true);

        hook.onEnded(ended(outcome));

        verify(fills).ended(Fills.OBJECT, FILL, REQUEST, status, PERSON);
        verify(headers).status(FILL, status);
    }

    @Test
    void aRequestThatReviewedNoFillInLeavesTheHeaderAlone() {
        when(fills.ended(eq(Fills.OBJECT), eq(FILL), eq(REQUEST), any(), any())).thenReturn(false);

        hook.onEnded(ended(ApprovalOutcome.APPROVED));

        verify(headers, never()).status(any(), any());
    }

    @Test
    void whenTheHeaderCannotFollowTheWholeDecisionFailsWithoutAValue() {
        when(fills.ended(any(), any(), any(), any(), any())).thenReturn(true);
        doThrow(new BackgroundWriteException(BackgroundWriteException.Reason.RECORD_LOCKED)).when(headers)
                .status(any(), any());

        assertThatThrownBy(() -> hook.onEnded(ended(ApprovalOutcome.REJECTED)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("record_locked")
                .isNotInstanceOf(ApprovalHookRefusal.class);
    }
}

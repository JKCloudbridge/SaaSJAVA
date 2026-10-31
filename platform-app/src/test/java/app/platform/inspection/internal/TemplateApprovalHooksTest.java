package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.NumberSeries;
import app.platform.data.NumberSeriesException;
import app.platform.data.NumberSeriesException.Reason;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHookWriteException;
import app.platform.sharedkernel.approval.ApprovalHookWriter;
import app.platform.sharedkernel.approval.ApprovalHooks;
import app.platform.sharedkernel.approval.ApprovalHooks.DecisionContext;
import app.platform.sharedkernel.approval.ApprovalHooks.EndContext;
import app.platform.sharedkernel.approval.ApprovalHooks.SubmitContext;
import app.platform.sharedkernel.approval.ApprovalOutcome;
import app.platform.sheets.SheetLifecycle;
import app.platform.sheets.SheetLifecycle.Summary;
import app.platform.sheets.SheetMoveRefusal;
import app.platform.sheets.SheetStatus;
import app.platformapi.QueryFilterNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * The hook of the approval engine for a template (FA4, ADR-0176 section 2, ADR-0187, ADR-0188), with no database: the
 * records, the series and the sheets are fakes that remember what they were asked. What it must do: refuse a submit
 * with fixed words that never repeat what an author typed, number a template at the first approval only, never change a
 * number once it is written, and move the version to the status the review ended with.
 */
class TemplateApprovalHooksTest {

    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final String TYPED = "TYPED-BY-AN-AUTHOR";

    /** One header record, other headers by number, and the calls made. */
    private static class Fakes implements BackgroundRecords, NumberSeries, SheetLifecycle, ApprovalHookWriter {

        Map<String, Object> fields = new HashMap<>();
        final Map<String, UUID> numbersTaken = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        Optional<List<String>> missing = Optional.of(List.of());
        Optional<String> known = Optional.empty();
        boolean numberingFails;
        boolean writeFails;
        SheetMoveRefusal sheetRefusal;
        /** The owner object the sheets module was last asked about. */
        String lastOwnerObject;
        /** What the versions of the template add up to: by default a first Revision in draft, nothing else. */
        Summary summary = new Summary(0, 0, 0, 1, 0, 1, 1);
        int next = 1;

        // ---- BackgroundRecords ----

        @Override
        public Optional<DataRecord> find(String objectApiName, UUID id) {
            return id.equals(RECORD) ? Optional.of(record(RECORD, fields)) : Optional.empty();
        }

        @Override
        public Map<String, Object> engineValues(String objectApiName, DataRecord record) {
            return record.fields();
        }

        @Override
        public Optional<DataRecord> findAndHold(String objectApiName, UUID id) {
            return find(objectApiName, id);
        }

        @Override
        public List<DataRecord> scan(String objectApiName, QueryFilterNode selection, UUID afterId, int limit) {
            String wanted = String.valueOf(selection.values().get(0));
            UUID holder = numbersTaken.get(wanted);
            return holder == null ? List.of() : List.of(record(holder, Map.of("number", wanted)));
        }

        // ---- NumberSeries ----

        @Override
        public boolean declared(String seriesKey) {
            return true;
        }

        @Override
        public Optional<String> prefix(String seriesKey, Map<String, Object> values) {
            return Optional.of("WI/PRD/");
        }

        @Override
        public Optional<List<String>> missingParts(String seriesKey, Map<String, Object> values) {
            return missing;
        }

        @Override
        public Optional<String> known(String seriesKey, Map<String, Object> values) {
            return known;
        }

        @Override
        public String next(String seriesKey, Map<String, Object> values) {
            calls.add("next");
            if (numberingFails) {
                throw new NumberSeriesException(Reason.PART_MISSING);
            }
            return "WI/PRD/" + String.format("%02d", next++);
        }

        @Override
        public Optional<Plan> plan(String seriesKey, Map<String, Object> values) {
            return Optional.empty();
        }

        @Override
        public String start(String seriesKey, Map<String, Object> values, long first) {
            calls.add("start");
            return "WI/PRD/" + String.format("%02d", first);
        }

        // ---- SheetLifecycle ----

        @Override
        public int submit(String ownerObject, UUID ownerId, UUID requestId, UUID actor) {
            lastOwnerObject = ownerObject;
            calls.add("submit:" + requestId + ":" + actor);
            if (sheetRefusal != null) {
                throw sheetRefusal;
            }
            return 1;
        }

        @Override
        public boolean reviewed(String ownerObject, UUID ownerId, UUID requestId, SheetStatus result, UUID actor) {
            calls.add("reviewed:" + requestId + ":" + result + ":" + actor);
            return true;
        }

        @Override
        public int publish(String ownerObject, UUID ownerId, int versionNo, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int unpublish(String ownerObject, UUID ownerId, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int retire(String ownerObject, UUID ownerId, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, Summary> summaries(String ownerObject, java.util.Collection<UUID> ownerIds) {
            return Map.of(ownerIds.iterator().next(), summary);
        }

        @Override
        public java.util.List<VersionDates> dates(String ownerObject, java.util.Collection<UUID> ownerIds,
                java.time.Instant decidedSince) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Started startDraft(String ownerObject, UUID ownerId, int basedOn, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<Integer, UUID> reviewRequests(String ownerObject, UUID ownerId) {
            return Map.of();
        }

        @Override
        public Optional<SheetStatus> statusOf(String ownerObject, UUID ownerId, int versionNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Layout> layoutOf(String ownerObject, UUID ownerId, int versionNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Started startHeldDraft(String ownerObject, UUID ownerId, int basedOn, UUID requestId, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HeldDraft> heldDraft(String ownerObject, UUID ownerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retargetHeldDraft(String ownerObject, UUID ownerId, UUID requestId, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int closeHeldDraft(String ownerObject, UUID ownerId, UUID requestId, SheetStatus result, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Integer> revisionOf(String ownerObject, UUID ownerId, int versionNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean everPublished(String ownerObject, UUID ownerId) {
            throw new UnsupportedOperationException();
        }

        // ---- ApprovalHookWriter ----

        @Override
        public void set(Map<String, String> values) {
            calls.add("write:" + values.keySet());
            if (writeFails) {
                throw new ApprovalHookWriteException("unique_clash");
            }
            fields.putAll(values);
        }
    }

    private static DataRecord record(UUID id, Map<String, Object> fields) {
        return new DataRecord(id, "InspectionTemplate", null, PERSON, fields, 0, Instant.EPOCH, PERSON, Instant.EPOCH,
                PERSON, null, null);
    }

    private static TemplateApprovalHooks hooks(Fakes fakes) {
        var assignments = org.mockito.Mockito.mock(app.platform.data.NumberAssignments.class);
        org.mockito.Mockito.when(assignments.fixed(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        return new TemplateApprovalHooks(fakes, fakes, fakes, assignments, null, RecordSetsFixture.both());
    }

    private static Fakes withTemplate() {
        Fakes fakes = new Fakes();
        fakes.fields.put("name", TYPED);
        fakes.fields.put("category", "WI");
        fakes.fields.put("partA", "PRD");
        return fakes;
    }

    private static SubmitContext submit() {
        return new SubmitContext(REQUEST, "InspectionTemplate", RECORD, "process", 1, PERSON);
    }

    private static DecisionContext decision() {
        return new DecisionContext(REQUEST, "InspectionTemplate", RECORD, "process", 1, 0, 1, "Review", PERSON);
    }

    private static EndContext ended(ApprovalOutcome outcome, UUID by) {
        return new EndContext(REQUEST, "InspectionTemplate", RECORD, "process", 1, 0, outcome, by);
    }

    private static ApprovalHookRefusal refusal(Runnable work) {
        try {
            work.run();
        } catch (ApprovalHookRefusal refused) {
            return refused;
        }
        throw new AssertionError("The hook did not refuse");
    }

    // ---- what the hook is ----

    @Test
    void itServesTheTemplateObjectAndMayWriteTheNumberAndNothingElse() {
        ApprovalHooks hooks = hooks(new Fakes());

        assertThat(hooks.object()).isEqualTo("InspectionTemplate");
        assertThat(hooks.writes()).containsExactly("number");
    }

    @Test
    void itServesTheTemplateObjectOfEverySetAndMovesTheSheetOfTheSetTheCallNames() {
        Fakes fakes = withTemplate();
        TemplateApprovalHooks hooks = hooks(fakes);

        assertThat(hooks.objects()).containsExactly("InspectionTemplate", "EnvironmentTemplate");

        hooks.onSubmit(new SubmitContext(REQUEST, "EnvironmentTemplate", RECORD, "process", 1, PERSON));
        assertThat(fakes.lastOwnerObject).isEqualTo("EnvironmentTemplate");

        hooks.onSubmit(submit());
        assertThat(fakes.lastOwnerObject).isEqualTo("InspectionTemplate");
    }

    @Test
    void aTemplateOfAnObjectNoSetKnowsIsRefusedInFixedWords() {
        Fakes fakes = withTemplate();

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes)
                .onSubmit(new SubmitContext(REQUEST, "Other__c", RECORD, "process", 1, PERSON)));

        assertThat(refused.reason()).isEqualTo("template_missing");
        assertThat(fakes.calls).isEmpty();
    }

    // ---- submit ----

    @Test
    void aSubmitOfACompleteTemplateMovesTheDraftToReviewUnderTheRequest() {
        Fakes fakes = withTemplate();

        hooks(fakes).onSubmit(submit());

        assertThat(fakes.calls).containsExactly("submit:" + REQUEST + ":" + PERSON);
    }

    @Test
    void aSubmitOfARetiredTemplateIsRefusedAndNothingMoves() {
        Fakes fakes = withTemplate();
        fakes.fields.put("retired", true);

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).onSubmit(submit()));

        assertThat(refused.reason()).isEqualTo("template_retired");
        assertThat(fakes.calls).isEmpty();
    }

    @Test
    void aSubmitIsRefusedInTheWordsOfTheOneRuleForEveryStateThatCannotBeSent() {
        record Case(Summary summary, boolean retired, TemplateSendRule.Block expected) {
        }
        List<Case> cases = List.of(
                new Case(new Summary(1, 0, 0, 0, 0, 1, 1), false, TemplateSendRule.PUBLISHED),
                new Case(new Summary(1, 0, 0, 1, 0, 2, 2), false, TemplateSendRule.PUBLISHED),
                new Case(new Summary(0, 0, 1, 0, 0, 1, 1), false, TemplateSendRule.BEING_APPROVED),
                new Case(new Summary(0, 0, 0, 0, 0, 0, 0), false, TemplateSendRule.NO_DRAFT),
                new Case(new Summary(0, 0, 0, 1, 0, 1, 1), true, TemplateSendRule.RETIRED));
        for (Case one : cases) {
            Fakes fakes = withTemplate();
            fakes.summary = one.summary();
            fakes.fields.put("retired", one.retired());

            ApprovalHookRefusal refused = refusal(() -> hooks(fakes).onSubmit(submit()));

            assertThat(refused.reason()).isEqualTo(one.expected().reason());
            assertThat(refused.words()).isEqualTo(one.expected().words());
            assertThat(fakes.calls).as("nothing moves").isEmpty();
            // The view asks the same rule, so the button and the refusal agree.
            assertThat(TemplateSendRule.blocked(one.summary(), one.retired())).contains(one.expected());
        }
    }

    @Test
    void aSubmitThatTheRuleAllowsIsAcceptedBecauseTheViewAndTheHookAgree() {
        Fakes fakes = withTemplate();
        assertThat(TemplateSendRule.blocked(fakes.summary, false)).isEmpty();

        hooks(fakes).onSubmit(submit());

        assertThat(fakes.calls).containsExactly("submit:" + REQUEST + ":" + PERSON);
    }

    @Test
    void aSubmitWithoutACategoryPassesBecauseTheApproversChooseTheIdentity() {
        Fakes fakes = withTemplate();
        fakes.fields.remove("category");
        fakes.missing = Optional.empty();

        hooks(fakes).onSubmit(submit());

        assertThat(fakes.calls).containsExactly("submit:" + REQUEST + ":" + PERSON);
    }

    @Test
    void theFirstApprovalWithoutACategoryIsRefusedInWordsAndNothingIsNumbered() {
        Fakes fakes = withTemplate();
        fakes.fields.remove("category");

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).beforeApprove(decision(), fakes));

        assertThat(refused.reason()).isEqualTo("category_needed");
        assertThat(refused.words())
                .isEqualTo("Choose the identification (Edit), then approve again.");
        assertThat(fakes.calls).isEmpty();
        fakes.fields.put("category", "WI");
        hooks(fakes).beforeApprove(decision(), fakes);
        assertThat(fakes.fields).containsEntry("number", "WI/PRD/01");
    }

    @Test
    void aCategoryWithNoFormatIsRefusedAndNamesNeitherTheCategoryNorTheName() {
        Fakes fakes = withTemplate();
        fakes.fields.put("category", TYPED);
        fakes.missing = Optional.empty();

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).beforeApprove(decision(), fakes));

        assertThat(refused.reason()).isEqualTo("category_not_numbered");
        assertThat(refused.words()).doesNotContain(TYPED);
    }

    @Test
    void thePartsStillToFillInAreNamedByWhatTheyAreAndNeverByWhatWasTyped() {
        Fakes fakes = withTemplate();
        fakes.fields.put("partB", "has space " + TYPED);
        fakes.missing = Optional.of(List.of("partA", "partB"));

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).beforeApprove(decision(), fakes));

        assertThat(refused.reason()).isEqualTo("number_part_needed");
        assertThat(refused.words()).startsWith("Fill in Part A (capital letters").contains("and Part B")
                .doesNotContain(TYPED);
        assertThat(refused.words().length()).isLessThanOrEqualTo(300);
    }

    @Test
    void aPartThatIsNotInCapitalsIsRefusedInWordsThatNameNoValue() {
        Fakes fakes = withTemplate();
        fakes.fields.put("partB", "lower" + TYPED);

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).beforeApprove(decision(), fakes));

        assertThat(refused.reason()).isEqualTo("part_invalid");
        assertThat(refused.words()).startsWith("Fill in Part B").doesNotContain(TYPED);
        fakes.fields.put("partB", "UPPER-1");
        hooks(fakes).beforeApprove(decision(), fakes);
        assertThat(fakes.calls).containsExactly("next", "write:[number]");
    }

    @Test
    void aNumberAnotherTemplateAlreadyHasIsRefusedAtTheFirstApprovalButMyOwnIsNotAClash() {
        Fakes fakes = withTemplate();
        fakes.known = Optional.of("CP/PRD/AB-12");
        fakes.numbersTaken.put("CP/PRD/AB-12", UUID.randomUUID());

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).beforeApprove(decision(), fakes));

        assertThat(refused.reason()).isEqualTo("number_in_use");
        assertThat(refused.words()).doesNotContain("CP/PRD");
        assertThat(fakes.calls).isEmpty();
        fakes.numbersTaken.put("CP/PRD/AB-12", RECORD);
        hooks(fakes).beforeApprove(decision(), fakes);
        assertThat(fakes.calls).containsExactly("next", "write:[number]");
    }

    @Test
    void aTemplateThatAlreadyHasANumberIsNotCheckedForNumberingAgain() {
        Fakes fakes = withTemplate();
        fakes.fields.put("number", "WI/PRD/01");
        fakes.fields.remove("category");
        fakes.missing = Optional.empty();

        hooks(fakes).beforeApprove(decision(), fakes);
        hooks(fakes).onSubmit(submit());

        assertThat(fakes.calls).containsExactly("submit:" + REQUEST + ":" + PERSON);
    }

    @Test
    void aRefusalOfTheSheetBecomesARefusalOfTheHookWithTheSheetsOwnFixedWords() {
        Fakes fakes = withTemplate();
        fakes.sheetRefusal = new SheetMoveRefusal(SheetMoveRefusal.Reason.EMPTY);

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).onSubmit(submit()));

        assertThat(refused.reason()).isEqualTo("sheet_empty");
        assertThat(refused.words()).isEqualTo(SheetMoveRefusal.Reason.EMPTY.words());
    }

    @Test
    void aLongRefusalOfTheSheetIsCutToWhatARefusalMayCarry() {
        Fakes fakes = withTemplate();
        fakes.sheetRefusal = new SheetMoveRefusal(SheetMoveRefusal.Reason.INVALID, "x".repeat(900));

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).onSubmit(submit()));

        assertThat(refused.words().length()).isLessThanOrEqualTo(300);
    }

    @Test
    void aTemplateThatIsGoneIsRefusedWithoutAName() {
        Fakes fakes = new Fakes();
        SubmitContext other = new SubmitContext(REQUEST, "InspectionTemplate", UUID.randomUUID(), "p", 1, PERSON);

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).onSubmit(other));

        assertThat(refused.reason()).isEqualTo("template_missing");
    }

    // ---- the first approval gives the number ----

    @Test
    void theFirstApprovalTakesTheNumberAndWritesItThroughTheWriterOnce() {
        Fakes fakes = withTemplate();

        hooks(fakes).beforeApprove(decision(), fakes);

        assertThat(fakes.calls).containsExactly("next", "write:[number]");
        assertThat(fakes.fields).containsEntry("number", "WI/PRD/01");
    }

    @Test
    void aLaterApprovalFindsTheNumberAndDoesNothing() {
        Fakes fakes = withTemplate();
        hooks(fakes).beforeApprove(decision(), fakes);
        fakes.calls.clear();

        hooks(fakes).beforeApprove(decision(), fakes);

        assertThat(fakes.calls).isEmpty();
        assertThat(fakes.fields).containsEntry("number", "WI/PRD/01");
    }

    @Test
    void aTemplateThatCannotBeNumberedNowRefusesTheApprovalInWordsAndWritesNothing() {
        Fakes fakes = withTemplate();
        fakes.numberingFails = true;

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).beforeApprove(decision(), fakes));

        assertThat(refused.reason()).isEqualTo("not_numbered");
        assertThat(fakes.calls).containsExactly("next");
        assertThat(fakes.fields).doesNotContainKey("number");
    }

    @Test
    void aWriteThatTheStoreRefusesBecauseTheNumberIsTakenIsARefusalNotAFailure() {
        Fakes fakes = withTemplate();
        fakes.writeFails = true;

        ApprovalHookRefusal refused = refusal(() -> hooks(fakes).beforeApprove(decision(), fakes));

        assertThat(refused.reason()).isEqualTo("number_in_use");
        assertThat(refused.words()).doesNotContain("WI/PRD");
    }

    @Property
    void aNumberOnceWrittenNeverChangesHoweverManyApprovalsFollow(@ForAll @IntRange(min = 1, max = 12) int approvals) {
        Fakes fakes = withTemplate();
        TemplateApprovalHooks hooks = hooks(fakes);

        for (int i = 0; i < approvals; i++) {
            hooks.beforeApprove(decision(), fakes);
        }

        assertThat(fakes.fields).containsEntry("number", "WI/PRD/01");
        assertThat(fakes.calls.stream().filter("next"::equals).count()).as("the series was asked once").isEqualTo(1);
        assertThat(fakes.calls.stream().filter(call -> call.startsWith("write")).count()).isEqualTo(1);
    }

    // ---- the end of a review ----

    @Test
    void theEndOfAReviewMovesTheVersionToTheStatusItEndedWith() {
        Fakes fakes = withTemplate();
        TemplateApprovalHooks hooks = hooks(fakes);

        hooks.onEnded(ended(ApprovalOutcome.APPROVED, PERSON));
        hooks.onEnded(ended(ApprovalOutcome.REJECTED, PERSON));
        hooks.onEnded(ended(ApprovalOutcome.RECALLED, PERSON));
        hooks.onEnded(ended(ApprovalOutcome.CANCELLED, null));

        assertThat(fakes.calls).containsExactly(
                "reviewed:" + REQUEST + ":APPROVED:" + PERSON,
                "reviewed:" + REQUEST + ":REJECTED:" + PERSON,
                "reviewed:" + REQUEST + ":WITHDRAWN:" + PERSON,
                "reviewed:" + REQUEST + ":WITHDRAWN:null");
    }

    @Test
    void aFailureOfTheSheetIsNotSwallowed() {
        Fakes fakes = new Fakes() {
            @Override
            public boolean reviewed(String ownerObject, UUID ownerId, UUID requestId, SheetStatus result, UUID actor) {
                throw new IllegalStateException("the sheet failed");
            }
        };

        assertThatThrownBy(() -> hooks(fakes).onEnded(ended(ApprovalOutcome.APPROVED, PERSON)))
                .isInstanceOf(IllegalStateException.class);
    }
}

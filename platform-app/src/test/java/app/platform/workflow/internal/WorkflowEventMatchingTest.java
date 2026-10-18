package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.WorkflowDefinition.Kind;
import app.platform.metadata.WorkflowDefinition.Trigger;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which workflow starts on which event (ADR-0137): the kind of change and, for a field change, the names of the changed
 * fields. A truth table without a database; the rest of the handler is tested against the real outbox.
 */
class WorkflowEventMatchingTest {

    private static Trigger of(Kind kind, String... fields) {
        return new Trigger(kind, List.of(fields), null, null, ZoneId.of("UTC"), LocalTime.MIDNIGHT, null, null, null);
    }

    @Test
    void aCreationStartsOnlyTheCreationTrigger() {
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_CREATED), "record.created", Set.of())).isTrue();
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_UPDATED), "record.created", Set.of())).isFalse();
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_DELETED), "record.created", Set.of())).isFalse();
        assertThat(WorkflowEventHandler.matches(of(Kind.FIELD_CHANGED, "a__c"), "record.created", Set.of("a__c")))
                .isFalse();
    }

    @Test
    void anUpdateStartsTheUpdateTriggerAndAFieldChangeThatNamesAChangedField() {
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_UPDATED), "record.updated", Set.of("a__c"))).isTrue();
        assertThat(WorkflowEventHandler.matches(of(Kind.FIELD_CHANGED, "a__c", "b__c"), "record.updated",
                Set.of("b__c", "c__c"))).isTrue();
        assertThat(WorkflowEventHandler.matches(of(Kind.FIELD_CHANGED, "a__c"), "record.updated", Set.of("b__c")))
                .isFalse();
        assertThat(WorkflowEventHandler.matches(of(Kind.FIELD_CHANGED, "a__c"), "record.updated", Set.of())).isFalse();
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_CREATED), "record.updated", Set.of("a__c"))).isFalse();
    }

    @Test
    void aDeleteStartsOnlyTheDeleteTrigger() {
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_DELETED), "record.deleted", Set.of())).isTrue();
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_UPDATED), "record.deleted", Set.of())).isFalse();
    }

    @Test
    void aScheduledTriggerNeverStartsOnAnEventAndOtherEventsStartNothing() {
        for (Kind kind : List.of(Kind.EVERY_DAY, Kind.EVERY_WEEK, Kind.DATE_TIME, Kind.DAYS_AFTER_DATE)) {
            for (String type : List.of("record.created", "record.updated", "record.deleted")) {
                assertThat(WorkflowEventHandler.matches(of(kind), type, Set.of("a__c"))).as(kind + " on " + type)
                        .isFalse();
            }
        }
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_CREATED), "record.restored", Set.of())).isFalse();
        assertThat(WorkflowEventHandler.matches(of(Kind.RECORD_DELETED), "record.purged", Set.of())).isFalse();
    }

    private static Trigger ofApproval(String process, String outcome) {
        return new Trigger(Kind.APPROVAL_OUTCOME, List.of(), null, null, ZoneId.of("UTC"), LocalTime.MIDNIGHT, null,
                null, null, process, outcome);
    }

    @Test
    void theEndOfAnApprovalStartsOnlyTheApprovalTriggerThatNamesItsProcessAndOutcomeOrNamesNone() {
        assertThat(WorkflowEventHandler.matches(ofApproval("Large__c", "APPROVED"), "approval.approved", Set.of(),
                "Large__c", "APPROVED")).isTrue();
        assertThat(WorkflowEventHandler.matches(ofApproval("Large__c", "APPROVED"), "approval.rejected", Set.of(),
                "Large__c", "REJECTED")).as("another outcome").isFalse();
        assertThat(WorkflowEventHandler.matches(ofApproval("Large__c", "APPROVED"), "approval.approved", Set.of(),
                "Small__c", "APPROVED")).as("another process").isFalse();
        assertThat(WorkflowEventHandler.matches(ofApproval(null, null), "approval.recalled", Set.of(), "Any__c",
                "RECALLED")).as("a trigger that names neither takes any").isTrue();
        assertThat(WorkflowEventHandler.matches(ofApproval(null, "REJECTED"), "approval.approved", Set.of(),
                "Any__c", "APPROVED")).isFalse();
    }

    @Test
    void anApprovalTriggerNeverStartsOnARecordEventAndARecordTriggerNeverOnTheEndOfAnApproval() {
        for (String type : List.of("record.created", "record.updated", "record.deleted")) {
            assertThat(WorkflowEventHandler.matches(ofApproval(null, null), type, Set.of("a__c"), null, null))
                    .as(type).isFalse();
        }
        for (Kind kind : List.of(Kind.RECORD_CREATED, Kind.RECORD_UPDATED, Kind.RECORD_DELETED, Kind.FIELD_CHANGED)) {
            assertThat(WorkflowEventHandler.matches(of(kind, "a__c"), "approval.approved", Set.of("a__c"), "P__c",
                    "APPROVED")).as(kind.toString()).isFalse();
        }
        assertThat(WorkflowEventHandler.matches(ofApproval(null, null), "approval.submitted", Set.of(), "P__c",
                "APPROVED")).as("the start of an approval starts nothing").isFalse();
    }
}

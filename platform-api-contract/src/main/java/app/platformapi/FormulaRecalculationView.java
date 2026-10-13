package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Where the stored formula results of an object stand (Sprint 16A): whether they are current for the formulas the
 * object has now, and the recalculation that brings them there.
 *
 * @param objectApiName the object
 * @param state {@code NONE} (the object has no formulas), {@code CURRENT} (the stored results belong to the formulas
 *        as they are now: a formula field can be used in a filter or an order), {@code NEEDED} (a formula was edited
 *        and the recalculation has not started), {@code PENDING} (asked for, about to start), {@code RUNNING}
 *        (records are being recalculated), {@code CLOSING} (the pass has gone through every record and is waiting to
 *        look once more at the records changed meanwhile). While the state is not {@code NONE} or {@code CURRENT} a
 *        filter or an order on a formula field is refused with the reason.
 * @param formulaFields how many formula fields the object has
 * @param startedBy {@code PUBLISH}, {@code MANUAL} or {@code RESUME} for the pass in flight; absent when none
 * @param startedAt when the pass in flight began; absent when none
 * @param recordsExamined records the pass in flight has looked at
 * @param recordsChanged records whose results it has rewritten
 * @param recordsUnavailable records with a formula that could not be calculated from their values (the stored result
 *        of that formula is removed)
 * @param lastFinishedAt when the last pass finished; absent when none has
 * @param lastRecordsExamined records the last finished pass looked at
 * @param lastRecordsChanged records the last finished pass rewrote
 * @param lastRecordsUnavailable records the last finished pass could not calculate
 */
public record FormulaRecalculationView(@NotNull String objectApiName, @NotNull String state,
        @NotNull Integer formulaFields, String startedBy, Instant startedAt, @NotNull Long recordsExamined,
        @NotNull Long recordsChanged, @NotNull Long recordsUnavailable, Instant lastFinishedAt,
        @NotNull Long lastRecordsExamined, @NotNull Long lastRecordsChanged, @NotNull Long lastRecordsUnavailable) {
}

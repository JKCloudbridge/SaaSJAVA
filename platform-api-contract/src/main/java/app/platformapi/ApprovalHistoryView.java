package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the history of an approval (Sprint 24, ADR-0145). It names people, steps and stable reasons and, for a
 * decision, the comment the person wrote. It never shows a value of the record. The comment is text of a person and is
 * never logged.
 *
 * @param id the line
 * @param stepPosition the step it is about, or null
 * @param kind what happened: {@code SUBMITTED}, {@code STEP_ACTIVATED}, {@code TASK_APPROVED} (a person approved),
 *        {@code STEP_APPROVED} (the step is complete), {@code APPROVED} (the request is approved), {@code REJECTED}
 *        (a person rejected, which ends the request), {@code RECALLED}, {@code CANCELLED}, {@code ASSIGNEE_DROPPED},
 *        {@code REASSIGNED}, {@code ASSIGNED}, {@code ATTENTION_RAISED}, {@code ATTENTION_CLEARED},
 *        {@code OUTCOME_APPLIED}, {@code OUTCOME_FAILED}, {@code OUTCOME_REAPPLIED}, {@code LOCK_OVERRIDDEN} or
 *        {@code STEP_SKIPPED} (nobody could be asked and the process lets the step be skipped; FA4A, ADR-0187)
 * @param actor who did it, or null when the platform did
 * @param onBehalfOf the person the actor acted for (a delegation), or null
 * @param target the person a task was handed to or taken from, or null
 * @param reason a stable key, or null
 * @param comment the comment of the person, or null
 * @param at when
 */
public record ApprovalHistoryView(@NotNull UUID id, Integer stepPosition, @NotNull String kind,
        ApprovalPersonView actor,
        ApprovalPersonView onBehalfOf, ApprovalPersonView target, String reason, String comment, @NotNull Instant at) {

    @Override
    public String toString() {
        return "ApprovalHistoryView[" + kind + "]";
    }
}

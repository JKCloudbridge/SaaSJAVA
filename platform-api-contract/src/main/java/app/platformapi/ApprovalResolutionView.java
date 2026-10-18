package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Who each step of an approval would ask for a record (Sprint 24, ADR-0146). Nothing was stored, locked or sent.
 *
 * @param steps the steps in order, each with the people who would be asked
 */
public record ApprovalResolutionView(@NotNull List<ResolvedStep> steps) {

    /** Copies the list. */
    public ApprovalResolutionView {
        steps = List.copyOf(steps);
    }

    /**
     * One step as it would be resolved for the record.
     *
     * @param name the name of the step
     * @param label its label
     * @param people the people who would be asked
     * @param left why people were left out ({@code inactive}, {@code unlicensed}, {@code self_approval_excluded}): each
     *        reason once with how many; the people are not named
     * @param usesFallback whether the fallback approver would be asked because the rules name nobody who can decide
     * @param nobody whether nobody at all would be asked (a request would be refused if this is the first step, and
     * would
     *        wait for an administrator otherwise)
     */
    public record ResolvedStep(@NotNull String name, @NotNull String label, @NotNull List<ApprovalPersonView> people,
            @NotNull Map<String, Integer> left, @NotNull Boolean usesFallback, @NotNull Boolean nobody) {

        /** Copies the list and the map. */
        public ResolvedStep {
            people = List.copyOf(people);
            left = Map.copyOf(left);
        }
    }
}

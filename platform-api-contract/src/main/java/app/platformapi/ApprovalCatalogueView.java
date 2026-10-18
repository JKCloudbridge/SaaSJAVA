package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What an approval process can be made of, and the words of its states, as the server says it (Sprint 24, ADR-0145):
 * the
 * screens build their forms and their filters from this and hold no list of approver kinds, completion rules, states,
 * outcomes or limits.
 *
 * @param approverKinds the kinds of approver rule
 * @param completions the completion rules of a step
 * @param outcomes the outcomes that may set fields of the record
 * @param states the states of a request
 * @param stepStates the states of a step
 * @param limits the numbers the platform enforces
 */
public record ApprovalCatalogueView(@NotNull List<ApproverKind> approverKinds, @NotNull List<Choice> completions,
        @NotNull List<Choice> outcomes, @NotNull List<Choice> states, @NotNull List<Choice> stepStates,
        @NotNull Limits limits) {

    /** Copies the lists. */
    public ApprovalCatalogueView {
        approverKinds = List.copyOf(approverKinds);
        completions = List.copyOf(completions);
        outcomes = List.copyOf(outcomes);
        states = List.copyOf(states);
        stepStates = List.copyOf(stepStates);
    }

    /**
     * One kind of approver rule.
     *
     * @param kind the code
     * @param summary who it asks, in words
     * @param valueRefersTo what the value names: {@code NOTHING} (no value), {@code USER}, {@code PROFILE},
     *        {@code ROLE}, {@code GROUP} or {@code USER_FIELD} (a user field of the object)
     */
    public record ApproverKind(@NotNull String kind, @NotNull String summary, @NotNull String valueRefersTo) {
    }

    /**
     * One code with its words.
     *
     * @param code the code
     * @param summary what it means, in words
     */
    public record Choice(@NotNull String code, @NotNull String summary) {
    }

    /**
     * The numbers the platform enforces.
     *
     * @param maxSteps steps in one process
     * @param maxApproversPerStep approver rules in one step
     * @param maxOutcomeFields fields one outcome sets
     * @param maxCommentLength characters in a comment
     * @param maxDelegationDays days one delegation may last
     */
    public record Limits(@NotNull Integer maxSteps, @NotNull Integer maxApproversPerStep,
            @NotNull Integer maxOutcomeFields, @NotNull Integer maxCommentLength, @NotNull Integer maxDelegationDays) {
    }
}

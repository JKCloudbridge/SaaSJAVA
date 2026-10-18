package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One approval process of an object as the setup screens show it (Sprint 24). The criteria and the labels are text the
 * organization typed, so the record never prints.
 *
 * @param apiName the permanent name, for example {@code LargeExpense__c}
 * @param objectApiName the object it belongs to
 * @param label the label people read
 * @param description what the process is for
 * @param active whether new requests may start under it
 * @param definitionVersion the number of the definition new requests use; it grows only when the criteria, steps,
 *        outcomes, fallback or self-approval rule change
 * @param entryCriteria the entry criteria, or null
 * @param allowSelfApproval whether a person who submits may also be asked to decide
 * @param steps the steps in order
 * @param outcomes what the end of a request sets on the record
 * @param fallback who is asked when a step names nobody who can decide, or null
 * @param remindAfterHours hours an active step may wait before the people asked are reminded, or null for never
 * @param escalateAfterHours hours an active step may wait before the fallback approver is asked as well, once, or
 *        null for never
 * @param version the version to send back when changing it
 */
public record ApprovalProcessView(@NotNull String apiName, @NotNull String objectApiName, @NotNull String label,
        @NotNull String description, @NotNull Boolean active, @NotNull Integer definitionVersion, String entryCriteria,
        @NotNull Boolean allowSelfApproval, @NotNull List<ApprovalStepSpec> steps,
        @NotNull ApprovalOutcomesSpec outcomes,
        ApprovalApproverSpec fallback, Integer remindAfterHours, Integer escalateAfterHours, @NotNull Long version) {

    /** Copies the list. */
    public ApprovalProcessView {
        steps = List.copyOf(steps);
    }

    @Override
    public String toString() {
        return "ApprovalProcessView[redacted]";
    }
}

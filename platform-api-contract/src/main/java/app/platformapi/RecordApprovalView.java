package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The approval panel of one record (Sprint 24, ADR-0152): whether the record is locked, the request that is pending or
 * the last one that ended, which processes the caller could submit it under, and what the caller may do now. The record
 * must be readable by the caller; otherwise it is answered as a record that does not exist.
 *
 * @param available whether the object has any approval process (the panel is drawn only then, or when a request exists)
 * @param locked whether the record is locked by a pending request
 * @param current the request that is pending, or the latest one that ended, or null when there was never one
 * @param processes the active processes of the object whose entry criteria the record meets, which the caller may
 *        submit it under; empty when none fits or the caller may not submit
 * @param notSubmittableReason a stable key saying why the caller cannot submit although the object has a process
 *        ({@code feature_off}, {@code already_pending}, {@code no_process_applies}, {@code not_editable}), or null
 * @param actions what the caller may do now
 */
public record RecordApprovalView(@NotNull Boolean available, @NotNull Boolean locked, ApprovalInstanceView current,
        @NotNull List<ApprovalChoiceView> processes, String notSubmittableReason,
        @NotNull ApprovalActionsView actions) {

    /** Copies the list. */
    public RecordApprovalView {
        processes = List.copyOf(processes);
    }

    /** One process the record could be submitted under.
     *
     * @param apiName the process
     * @param label its label
     */
    public record ApprovalChoiceView(@NotNull String apiName, @NotNull String label) {
    }
}

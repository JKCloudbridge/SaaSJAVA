package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Asks who each step would ask for a record (Sprint 24, ADR-0146). The record is read with the caller's own
 * permissions;
 * the steps are worked out exactly as a real request works them out, and nothing is stored, locked or sent.
 *
 * @param recordId a record of the object the caller can read
 * @param steps the steps to try
 * @param fallback the fallback approver to try, or null
 * @param allowSelfApproval whether a person who submits may also be asked; the caller is taken as the submitter
 */
public record ResolveApprovalRequest(@NotNull UUID recordId,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid ApprovalStepSpec> steps, @Valid ApprovalApproverSpec fallback,
        Boolean allowSelfApproval) {

    /** Copies the list. */
    public ResolveApprovalRequest {
        steps = steps == null ? null : List.copyOf(steps);
    }

    @Override
    public String toString() {
        return "ResolveApprovalRequest[redacted]";
    }
}

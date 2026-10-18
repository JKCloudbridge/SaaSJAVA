package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Changes an approval process (Sprint 24, ADR-0145). The API name and the object never change. A change of the entry
 * criteria, the steps, the outcomes, the fallback or the self-approval rule makes a new version of the definition; a
 * change
 * of the label, the description or only whether it is active does not, and a request already pending keeps the version
 * it
 * started under.
 *
 * @param label the label people read
 * @param description what the process is for, optional
 * @param active whether new requests may start under the process
 * @param entryCriteria an optional condition that must be true for a record to be submitted
 * @param allowSelfApproval whether a person who submits may also be asked to decide
 * @param steps the steps in order
 * @param outcomes what the end of the request sets on the record
 * @param fallback who is asked when a step names nobody who can decide, or null
 * @param version the version of the process the caller last read; a change on top of a newer one is refused
 */
public record UpdateApprovalProcessRequest(@NotBlank @Size(max = 80) String label, @Size(max = 500) String description,
        @NotNull Boolean active, @Size(max = 4000) String entryCriteria, @NotNull Boolean allowSelfApproval,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid ApprovalStepSpec> steps, @Valid ApprovalOutcomesSpec outcomes,
        @Valid ApprovalApproverSpec fallback, @NotNull Long version) {

    /** Copies the list. */
    public UpdateApprovalProcessRequest {
        steps = steps == null ? null : List.copyOf(steps);
    }

    @Override
    public String toString() {
        return "UpdateApprovalProcessRequest[redacted]";
    }
}

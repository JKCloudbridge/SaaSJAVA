package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Adds an approval process to an object (Sprint 24, ADR-0145): a record that meets the entry criteria can be submitted,
 * the
 * steps decide it in order, and the outcomes set fields of the record when it ends. The text is typed by a person, so
 * it
 * never prints.
 *
 * @param name the name to build the API name from; the platform stores it with the ending {@code __c}
 * @param label the label people read
 * @param description what the process is for, optional
 * @param active whether new requests may start under the process (default true)
 * @param entryCriteria an optional condition in the expression language that must be true for a record to be submitted
 *        (it may use {@code TODAY()} and {@code NOW()}); left out, any record may be submitted
 * @param allowSelfApproval whether a person who submits may also be asked to decide; default false (the person is
 *        left out of the people asked, with the reason recorded)
 * @param steps the steps in order (at least one)
 * @param outcomes what the end of the request sets on the record; left out, nothing
 * @param fallback who is asked when a step names nobody who can decide: a {@code USER} or a {@code GROUP}; left out, a
 *        request whose first step names nobody is refused
 */
public record CreateApprovalProcessRequest(@NotBlank @Size(max = 40) String name,
        @NotBlank @Size(max = 80) String label,
        @Size(max = 500) String description, Boolean active, @Size(max = 4000) String entryCriteria,
        Boolean allowSelfApproval, @NotEmpty @Size(max = 20) List<@NotNull @Valid ApprovalStepSpec> steps,
        @Valid ApprovalOutcomesSpec outcomes, @Valid ApprovalApproverSpec fallback) {

    /** Copies the list. */
    public CreateApprovalProcessRequest {
        steps = steps == null ? null : List.copyOf(steps);
    }

    @Override
    public String toString() {
        return "CreateApprovalProcessRequest[redacted]";
    }
}
